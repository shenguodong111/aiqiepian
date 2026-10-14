<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import MediaPlayer from '../components/MediaPlayer.vue'
import { CLIP_STATUS, fmtDuration } from '../utils/format'

const route = useRoute()
const router = useRouter()

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = 15
const loading = ref(true)
const rooms = ref([])
const sessions = ref([])
// 评分/状态筛选持久化到 localStorage：刷新/切页后不再被重置回默认值。
// 会话筛选不持久化——它来自"查看片段"跳转的 route.query，持久化会把页面卡在某会话。
const FILTER_KEY = 'clips.filters'
const saved = (() => { try { return JSON.parse(localStorage.getItem(FILTER_KEY) || '{}') } catch { return {} } })()
const filters = reactive({
  sessionId: route.query.sessionId || '',
  minScore: Number.isFinite(saved.minScore) ? saved.minScore : 0,
  status: saved.status || '',
})
const checked = ref(new Set())
const editClip = ref(null) // { form: {start_sec, end_sec} }
const editing = ref(false)
const playClip = ref(null) // 正在预览的片段

function play(c) { playClip.value = c }

async function load(silent = false) {
  try {
    const d = await http.get('/clips', { sessionId: filters.sessionId, minScore: filters.minScore || undefined, status: filters.status, page: page.value, size }, { silent })
    list.value = d.list
    total.value = d.total
    checked.value = new Set()
  } catch { if (!silent) toast.error('加载片段失败') } finally { loading.value = false }
}

async function loadOptions() {
  try {
    rooms.value = (await http.get('/rooms', { size: 100 }, { silent: true })).list
    const d = await http.get('/recordings', { status: 'history', size: 100 }, { silent: true })
    sessions.value = d.list
  } catch { /* 忽略 */ }
}

function applyFilters() {
  page.value = 1
  try { localStorage.setItem(FILTER_KEY, JSON.stringify({ minScore: filters.minScore, status: filters.status })) } catch { /* 隐私模式等忽略 */ }
  load()
}

async function makeWork(clip) {
  try {
    await http.post(`/clips/${clip.id}/make-work`)
    toast.success('作品已创建，成片任务已入队')
    load(true)
  } catch { /* http 已 toast */ }
}

async function discard(clip) {
  try {
    await http.post(`/clips/${clip.id}/discard`)
    toast.success('片段已丢弃（软删，可在"已丢弃"筛选中恢复）')
    load(true)
  } catch { /* http 已 toast */ }
}

async function restore(clip) {
  try {
    await http.post(`/clips/${clip.id}/restore`)
    toast.success('片段已恢复为待成片')
    load(true)
  } catch { /* http 已 toast */ }
}

// 彻底删除片段：级联删除其派生作品与产物文件（不可恢复）；含已发布作品则跳过不删
async function hardDelete(clip) {
  if (!window.confirm(`彻底删除片段 #${clip.id}？会连带删除它生成的作品及成片/封面/字幕文件（不可恢复）。若含已发布作品，则该片段保留不删。`)) return
  try {
    const r = await http.del(`/clips/${clip.id}`)
    if (r && r.removed_clips > 0) {
      const mb = r.freed_bytes ? (r.freed_bytes / 1048576).toFixed(1) : '0'
      toast.success(`片段已删除${r.removed_works ? `（含 ${r.removed_works} 个作品）` : ''}，回收 ${mb} MB`)
    } else if (r && r.skipped_published) {
      toast.error('该片段含已发布作品，未删除；请到剪辑工作台强制删除该作品后再试')
    } else {
      toast.info('未删除')
    }
    load(true)
  } catch { /* http 已 toast */ }
}

function openEdit(clip) { editClip.value = { clip, form: { start_sec: clip.start_sec, end_sec: clip.end_sec } } }
async function saveEdit() {
  const { clip, form } = editClip.value
  if (form.start_sec < 0 || form.end_sec <= form.start_sec) return toast.error('时间范围无效：需 0 ≤ 起 < 止')
  editing.value = true
  try {
    await http.put(`/clips/${clip.id}`, { start_sec: form.start_sec, end_sec: form.end_sec })
    toast.success('时间范围已更新')
    editClip.value = null
    load(true)
  } catch { /* http 已 toast */ } finally { editing.value = false }
}

const editDuration = computed(() => {
  if (!editClip.value) return 0
  return Math.max(0, editClip.value.form.end_sec - editClip.value.form.start_sec)
})

const allChecked = computed(() => list.value.length > 0 && list.value.every(c => checked.value.has(c.id)))
function toggleCheck(id) {
  const s = new Set(checked.value)
  s.has(id) ? s.delete(id) : s.add(id)
  checked.value = s
}
function toggleAll() { checked.value = allChecked.value ? new Set() : new Set(list.value.map(c => c.id)) }

async function batchMake() {
  const ids = [...checked.value].filter(id => list.value.find(c => c.id === id)?.status === 'PENDING')
  if (!ids.length) return toast.error('请勾选待成片的片段')
  try {
    const made = await http.post('/clips/batch-make-work', { ids })
    toast.success(`已批量创建 ${made.length} 个作品并入队成片`)
    load(true)
  } catch { /* http 已 toast */ }
}

async function analyzeSession() {
  if (!filters.sessionId) return
  try {
    await http.post(`/recordings/${filters.sessionId}/analyze`)
    toast.success('分析任务已入队，稍后刷新可见新片段')
  } catch { /* http 已 toast */ }
}

// 弹幕热度迷你柱：由热度值确定性生成
function heatBars(heat) {
  const bars = []
  let seed = heat
  for (let i = 0; i < 8; i++) {
    seed = (seed * 9301 + 49297) % 233280
    bars.push(3 + Math.floor((seed / 233280) * 13))
  }
  return bars
}

function scoreCls(score) { return score >= 85 ? 'green' : score >= 70 ? 'cyan' : 'orange' }

// ---------- M5 弹幕统计列（FR-P4-06） ----------
// top_danmaku 契约：JSON 数组字符串 [{"t":"哈哈","c":12},...]，解析失败/为空按无数据处理
function parseTop(json) {
  if (!json) return []
  try { const a = JSON.parse(json); return Array.isArray(a) ? a.filter(x => x && x.t) : [] } catch { return [] }
}
const rows = computed(() => list.value.map(c => ({ ...c, _top: parseTop(c.top_danmaku) })))
// 主播头像色块：由名字确定性哈希取色（与热度迷你柱同思路，无随机）
function anchorColor(name) {
  let h = 0
  for (const ch of String(name || '')) h = (h * 31 + ch.codePointAt(0)) % 360
  return `hsl(${h}, 55%, 45%)`
}
function statOr(v) { return v === null || v === undefined ? '--' : v }

function gotoRecordings() { router.push('/recordings') }

onMounted(() => { load(); loadOptions() })

/**
 * 会话总时长（秒）：recorded_sec 才是真实录到的秒数，duration_sec 只是"计划录多久"的上限，
 * 被掐断的会话它仍停在 4 小时——过去这里还把 duration_sec 当分钟又乘 60，显示成 240:00:00。
 */
function sessionTotalSec(id) {
  const s = sessions.value.find(x => x.id === id)
  if (!s) return 0
  return s.recorded_sec || s.duration_sec || 0
}
</script>

<template>
  <div class="clips-page">
    <div class="filter-bar">
      <select class="input" v-model="filters.sessionId" @change="applyFilters" style="max-width: 260px">
        <option value="">全部会话</option>
        <option v-for="s in sessions" :key="s.id" :value="s.id">
          #{{ s.id }} {{ s.room_name || '' }} · {{ (s.started_at || '').slice(5, 16) }}
        </option>
      </select>
      <label class="score-filter">
        <span class="hint">最低评分 <b class="num">{{ filters.minScore }}</b></span>
        <input type="range" min="0" max="98" v-model.number="filters.minScore" @change="applyFilters" />
      </label>
      <select class="input" v-model="filters.status" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="PENDING">待成片</option>
        <option value="RENDERED">已成片</option>
        <option value="DISCARDED">已丢弃</option>
      </select>
      <button v-if="filters.sessionId" class="btn" @click="analyzeSession">重新分析该会话</button>
      <span style="flex: 1"></span>
      <button class="btn primary" :disabled="!checked.size" @click="batchMake">批量成片（{{ checked.size }}）</button>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!list.length" icon="✂"
      text="还没有候选片段。录制会话结束后 AI 会自动分析并产出精彩片段；也可以到录制任务页手动触发分析。"
      action-label="前往录制任务" @action="gotoRecordings" />

    <template v-else>
      <div class="card table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 34px"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></th>
              <th>会话</th><th>房间</th><th>主播</th><th>起止时间</th><th>时长</th>
              <th style="min-width: 150px">精彩评分</th><th>AI 理由</th><th>弹幕热度</th>
              <th class="num">弹幕数</th><th class="num">密度(条/分)</th><th class="num">峰值(条/分)</th><th class="num">人数</th>
              <th style="min-width: 140px">高频弹幕</th><th>状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in rows" :key="c.id">
              <td><input type="checkbox" :checked="checked.has(c.id)" :disabled="c.status !== 'PENDING'" @change="toggleCheck(c.id)" /></td>
              <td class="num muted">#{{ c.session_id }}</td>
              <td>{{ c.room_name || '--' }}</td>
              <td>
                <span v-if="c.anchor_name" class="anchor-cell"><i class="anchor-dot" :style="{ background: anchorColor(c.anchor_name) }"></i>{{ c.anchor_name }}</span>
                <span v-else class="hint">--</span>
              </td>
              <td class="num">{{ fmtDuration(c.start_sec) }} – {{ fmtDuration(c.end_sec) }}</td>
              <td class="num">{{ fmtDuration(c.duration_sec) }}</td>
              <td>
                <div class="score-cell">
                  <div class="progress slim" style="width: 72px"><i :style="{ width: c.score + '%' }"></i></div>
                  <b class="num" :class="scoreCls(c.score) === 'green' ? 'sc-good' : ''">{{ c.score }}</b>
                </div>
              </td>
              <td>
                <span class="tip-wrap">
                  <span class="ai-reason trunc" style="max-width: 130px">{{ c.ai_reason }}</span>
                  <span class="tip">{{ c.ai_reason }}</span>
                </span>
              </td>
              <td>
                <span class="mini-bars"><i v-for="(h, i) in heatBars(c.danmaku_heat)" :key="i" :style="{ height: h + 'px' }"></i></span>
                <span class="hint num" style="margin-left: 6px">{{ c.danmaku_heat }}</span>
              </td>
              <td class="num">{{ statOr(c.danmaku_count) }}</td>
              <td class="num">{{ statOr(c.danmaku_per_min) }}</td>
              <td class="num">{{ statOr(c.peak_per_min) }}</td>
              <td class="num">{{ statOr(c.unique_users) }}</td>
              <td>
                <span v-if="c._top.length" class="tip-wrap">
                  <span class="top-dm trunc" style="max-width: 150px">
                    <b v-for="(t, i) in c._top.slice(0, 2)" :key="i" class="top-item">{{ t.t }} <span class="num x">x{{ t.c }}</span></b>
                  </span>
                  <span class="tip tip-multi"><i v-for="(t, i) in c._top" :key="i">{{ t.t }} x{{ t.c }}</i></span>
                </span>
                <span v-else class="hint">--</span>
              </td>
              <td><span class="badge" :class="{ PENDING: 'orange', RENDERED: 'green', DISCARDED: 'gray' }[c.status]">{{ CLIP_STATUS[c.status] || c.status }}</span></td>
              <td>
                <div class="row-actions" v-if="c.status !== 'DISCARDED'">
                  <button class="btn sm" @click="play(c)">▶ 播放</button>
                  <button class="btn sm primary" :disabled="c.status === 'RENDERED'" @click="makeWork(c)">生成作品</button>
                  <button class="btn sm ghost" @click="openEdit(c)">编辑时间</button>
                  <button class="btn sm ghost danger" @click="discard(c)">丢弃</button>
                  <button class="btn sm ghost danger" @click="hardDelete(c)">彻底删除</button>
                </div>
                <div v-else class="row-actions">
                  <button class="btn sm" @click="play(c)">▶ 播放</button>
                  <button class="btn sm" @click="restore(c)">恢复</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <Pagination :total="total" :page="page" :size="size" @update:page="p => { page = p; load(true) }" />
      </div>
    </template>

    <!-- 编辑时间弹窗 -->
    <Modal v-if="editClip" title="编辑片段时间范围" @close="editClip = null">
      <p class="hint" style="margin-bottom: 14px">修改后时长自动重算（单位：秒）。会话总时长 {{ fmtDuration(sessionTotalSec(editClip.clip.session_id)) }}。</p>
      <div class="time-inputs">
        <label class="field"><span>起始（秒）</span><input class="input num" type="number" min="0" v-model.number="editClip.form.start_sec" /></label>
        <span class="eq">→</span>
        <label class="field"><span>结束（秒）</span><input class="input num" type="number" min="1" v-model.number="editClip.form.end_sec" /></label>
      </div>
      <p class="muted" style="font-size: 13px">新时长：<b class="num">{{ fmtDuration(editDuration) }}</b></p>
      <div class="modal-foot">
        <button class="btn ghost" @click="editClip = null">取消</button>
        <button class="btn primary" :disabled="editing" @click="saveEdit">{{ editing ? '保存中…' : '保存' }}</button>
      </div>
    </Modal>

    <!-- 片段播放预览（成片优先，回落原始录制） -->
    <MediaPlayer v-if="playClip" type="clip" :id="playClip.id" :label="`片段预览 #${playClip.id} · ${fmtDuration(playClip.start_sec)}–${fmtDuration(playClip.end_sec)}`" @close="playClip = null" />
  </div>
</template>

<style scoped>
.clips-page { display: flex; column-gap: 0; flex-direction: column; }
.table-wrap { padding: 6px 14px 14px; overflow-x: auto; }
.score-filter { display: flex; align-items: center; gap: 8px; min-width: 180px; background: var(--surface); border: var(--border); border-radius: 9px; padding: 6px 12px; }
.score-filter input[type='range'] { width: 100px; }
.score-cell { display: flex; align-items: center; gap: 8px; }
.sc-good { color: #4ad07a; }
.ai-reason { color: var(--text-2); font-size: 12.5px; max-width: 130px; display: inline-block; }
td input[type='checkbox'] { accent-color: var(--accent); width: 14px; height: 14px; cursor: pointer; }
.time-inputs { display: flex; align-items: flex-end; gap: 10px; }
.time-inputs .field { flex: 1; margin-bottom: 0; }
.eq { color: var(--text-3); padding-bottom: 10px; }
/* M5 弹幕统计列（FR-P4-06） */
.anchor-cell { display: inline-flex; align-items: center; gap: 7px; white-space: nowrap; }
.anchor-dot { width: 20px; height: 20px; border-radius: 7px; flex: none; box-shadow: inset 0 0 0 1px rgb(255 255 255 / 12%); }
.top-dm { display: inline-flex; flex-direction: column; gap: 2px; font-size: 12px; color: var(--text-2); text-align: left; }
.top-item { font-weight: 500; white-space: nowrap; }
.top-item .x { color: var(--accent); font-size: 11px; margin-left: 2px; }
.tip-multi i { display: block; font-style: normal; line-height: 1.7; white-space: nowrap; }
</style>
