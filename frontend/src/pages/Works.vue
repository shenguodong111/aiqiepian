<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import MediaPlayer from '../components/MediaPlayer.vue'
import { WORK_STATUS, SUBTITLE_STYLES, ASPECTS, fmtDuration, fmtTime } from '../utils/format'

const router = useRouter()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = 12
const loading = ref(true)
const statusFilter = ref('')
const renderProgress = ref({}) // workId -> progress
const clipDurations = ref({}) // clipId -> duration_sec
const editing = ref(null) // { work, form }
const saving = ref(false)
const busy = ref(null)
const suggesting = ref(false)
const titleHint = ref('') // AI 生成依据（kind/summary）
const playWork = ref(null) // 正在预览成片的 work
let timer = null

function play(w) { playWork.value = w }
function onCoverClick(w) {
  if (w.output_path) play(w)
  else toast.info('该作品还没有成片，无法播放；点卡片下方「导出成片」渲染后即可预览')
}

async function load(silent = false) {
  try {
    const d = await http.get('/works', { status: statusFilter.value, page: page.value, size }, { silent })
    list.value = d.list
    total.value = d.total
    // 渲染中的进度（来自 RENDER RUNNING job）
    const jobs = await http.get('/jobs', { status: 'RUNNING', type: 'RENDER', size: 100 }, { silent: true })
    const map = {}
    for (const j of jobs.list) if (j.entity_type === 'work') map[j.entity_id] = j.progress
    renderProgress.value = map
  } catch { if (!silent) toast.error('加载作品失败') } finally { loading.value = false }
}

async function loadClipDurations() {
  try {
    const d = await http.get('/clips', { size: 200 }, { silent: true })
    const m = {}
    for (const c of d.list) m[c.id] = c.duration_sec
    clipDurations.value = m
  } catch { /* 忽略 */ }
}

function openEdit(w) {
  if (w.status === 'PUBLISHED') return toast.info('已发布作品为只读')
  titleHint.value = ''
  editing.value = {
    work: w,
    form: {
      title: w.title, description: w.description || '', tags: w.tags || '',
      subtitle_enabled: !!w.subtitle_enabled, subtitle_style: w.subtitle_style || 'big',
      aspect_ratio: w.aspect_ratio || '9:16',
    },
  }
}

async function saveEdit() {
  const f = editing.value.form
  if (!f.title.trim()) return toast.error('作品标题不能为空')
  const tagCount = f.tags ? f.tags.split(/[,，]/).map(t => t.trim()).filter(Boolean).length : 0
  if (tagCount > 10) return toast.error(`标签最多 10 个，当前 ${tagCount} 个`)
  saving.value = true
  try {
    await http.put(`/works/${editing.value.work.id}`, { ...f, title: f.title.trim() })
    toast.success('作品已保存')
    editing.value = null
    load(true)
  } catch { /* http 已 toast */ } finally { saving.value = false }
}

// FR-P5-05 智能标题：比赛=【战队1】VS【战队2】，日常=主播名+高频弹幕一句话
async function suggestTitle() {
  suggesting.value = true
  titleHint.value = ''
  try {
    const r = await http.post(`/works/${editing.value.work.id}/suggest-title`)
    editing.value.form.title = r.title
    const kindLabel = r.kind === 'esports' ? '比赛切片' : r.kind === 'daily' ? '主播日常切片' : '通用模板'
    titleHint.value = `${kindLabel} · ${r.summary}`
  } catch { /* http 已 toast */ } finally { suggesting.value = false }
}

// 改名后快速上传：保存并立即创建发布任务
const canPublishNow = computed(() => can(editing.value?.work, 'publish'))
async function saveAndPublish() {
  const f = editing.value.form
  if (!f.title.trim()) return toast.error('作品标题不能为空')
  saving.value = true
  try {
    await http.put(`/works/${editing.value.work.id}`, { ...f, title: f.title.trim() })
    await http.post(`/works/${editing.value.work.id}/publish`)
    toast.success('已保存并发布，进入发布队列')
    editing.value = null
    load(true)
  } catch { /* http 已 toast */ } finally { saving.value = false }
}

async function act(w, action, okMsg) {
  busy.value = `${w.id}:${action}`
  try {
    await http.post(`/works/${w.id}/${action}`)
    toast.success(okMsg)
    load(true)
  } catch { /* http 已 toast */ } finally { busy.value = null }
}

// 删除作品（连带成片/封面/字幕文件与审核/发布记录）；已发布需 force（仅删本地记录，远端稿件不撤回）
async function del(w) {
  const pub = w.status === 'PUBLISHED'
  const msg = pub
    ? '该作品已发布到 B 站。删除仅移除本地记录与产物文件，远端稿件不会被撤回。确认删除？'
    : `删除作品「${w.title}」及其成片/封面/字幕文件？此操作不可撤销。`
  if (!window.confirm(msg)) return
  busy.value = `${w.id}:delete`
  try {
    const r = await http.del(`/works/${w.id}`, pub ? { params: { force: true } } : undefined)
    const mb = r && r.freed_bytes ? (r.freed_bytes / 1048576).toFixed(1) : '0'
    toast.success(`已删除作品，回收 ${mb} MB`)
    load(true)
  } catch { /* http 已 toast */ } finally { busy.value = null }
}

const can = (w, action) => {
  const s = w.status
  if (action === 'export') return s === 'DRAFT'
  if (action === 'submit-review') return s === 'READY'
  if (action === 'publish') return ['READY', 'APPROVED', 'PUBLISH_FAILED'].includes(s)
  return false
}

const statusOptions = Object.entries(WORK_STATUS)

function gotoClips() { router.push('/clips') }

onMounted(() => {
  load()
  loadClipDurations()
  timer = setInterval(() => load(true), 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="works-page">
    <div class="filter-bar">
      <select class="input" v-model="statusFilter" @change="page = 1; load()">
        <option value="">全部状态</option>
        <option v-for="[k, v] in statusOptions" :key="k" :value="k">{{ v.label }}</option>
      </select>
      <span style="flex: 1"></span>
      <span class="hint">列表每 3 秒自动刷新渲染进度</span>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!list.length" icon="🎬"
      text="还没有作品。在片段分析页为高分片段点击「生成作品」，即可进入剪辑工作台导出成片。"
      action-label="前往片段分析" @action="gotoClips" />

    <template v-else>
      <div class="grid cols-4">
        <div v-for="w in list" :key="w.id" class="card work-card hover">
          <div class="cover-ph" :class="{ playable: w.output_path, 'no-output': !w.output_path }" @click="onCoverClick(w)" :title="w.output_path ? '播放成片' : '尚未生成成片，点击看提示'">
            ▶<span v-if="!w.output_path" class="no-output-tag hint">未出片</span>
            <span class="score-tag num">{{ w.score }}</span>
            <span v-if="w.status === 'RENDERING'" class="rendering-tag num">{{ renderProgress[w.id] ?? 0 }}%</span>
          </div>
          <div class="work-title" :title="w.title">{{ w.title }}</div>
          <div class="work-meta">
            <span class="badge" :class="WORK_STATUS[w.status]?.cls">{{ WORK_STATUS[w.status]?.label || w.status }}</span>
            <span class="hint num">{{ clipDurations[w.clip_id] != null ? fmtDuration(clipDurations[w.clip_id]) : '--' }} · {{ ASPECTS[w.aspect_ratio] || w.aspect_ratio }}</span>
          </div>
          <div v-if="w.status === 'RENDERING'" class="progress stripe" style="margin: 4px 0 2px">
            <i :style="{ width: (renderProgress[w.id] ?? 0) + '%' }"></i>
          </div>
          <div v-if="w.output_path" class="output-path hint trunc" :title="w.output_path">产物：{{ w.output_path }}</div>
          <div class="work-foot">
            <button class="btn sm" :disabled="!w.output_path" :title="w.output_path ? '播放成片' : '尚未生成成片'" @click="play(w)">▶ 播放</button>
            <button v-if="can(w, 'export')" class="btn sm primary" :disabled="busy === `${w.id}:export`" @click="act(w, 'export', '导出任务已入队')">导出成片</button>
            <button v-if="can(w, 'submit-review')" class="btn sm" :disabled="busy === `${w.id}:submit-review`" @click="act(w, 'submit-review', '已送审，进入审核中心')">送审</button>
            <button v-if="can(w, 'publish')" class="btn sm" :class="{ primary: w.status === 'APPROVED' }" :disabled="busy === `${w.id}:publish`" @click="act(w, 'publish', '发布任务已创建')">发布</button>
            <button class="btn sm ghost" @click="openEdit(w)">编辑</button>
            <button class="btn sm ghost danger" :disabled="busy === `${w.id}:delete`" @click="del(w)">删除</button>
          </div>
          <div v-if="w.status === 'PUBLISHED'" class="published-at hint">发布于 {{ fmtTime(w.updated_at) }}</div>
        </div>
      </div>
      <Pagination :total="total" :page="page" :size="size" @update:page="p => { page = p; load(true) }" />
    </template>

    <!-- 编辑弹窗 -->
    <Modal v-if="editing" :title="'编辑作品 #' + editing.work.id" @close="editing = null">
      <div class="field-block">
        <span class="field-title">作品标题 *</span>
        <div class="title-row">
          <input class="input" v-model="editing.form.title" placeholder="可直接改名，或点右侧 AI 生成" />
          <button class="btn sm" :disabled="suggesting" @click="suggestTitle">{{ suggesting ? '生成中…' : 'AI 生成标题' }}</button>
        </div>
        <div v-if="titleHint" class="hint" style="margin-top: 4px">{{ titleHint }}</div>
      </div>
      <label class="field"><span>简介</span><textarea class="input" v-model="editing.form.description" placeholder="支持 {房间} {日期} 等变量文案"></textarea></label>
      <label class="field"><span>标签（逗号分隔，≤10 个）</span><input class="input" v-model="editing.form.tags" placeholder="直播切片,AI剪辑" /></label>
      <label class="field"><span>封面</span>
        <div class="cover-upload">
          <div class="cover-thumb">图</div>
          <button class="btn sm" @click="toast.info('封面上传为本期占位，将使用自动生成的封面')">更换封面（占位）</button>
        </div>
      </label>
      <div class="form-row">
        <label class="check-line"><label class="switch"><input type="checkbox" v-model="editing.form.subtitle_enabled" /><span class="track"></span></label><span>启用字幕</span></label>
        <select class="input" v-model="editing.form.subtitle_style" :disabled="!editing.form.subtitle_enabled" style="width: 130px">
          <option v-for="(label, k) in SUBTITLE_STYLES" :key="k" :value="k">{{ label }}</option>
        </select>
      </div>
      <label class="field"><span>包装模板</span>
        <select class="input" v-model="editing.form.aspect_ratio">
          <option v-for="(label, k) in ASPECTS" :key="k" :value="k">{{ label }}</option>
        </select>
      </label>
      <div class="modal-foot">
        <button class="btn ghost" @click="editing = null">取消</button>
        <button v-if="canPublishNow" class="btn" :disabled="saving" @click="saveAndPublish">保存并快速发布</button>
        <button class="btn primary" :disabled="saving" @click="saveEdit">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </Modal>

    <!-- 成片播放预览 -->
    <MediaPlayer v-if="playWork" type="work" :id="playWork.id" :label="`成片预览 · ${playWork.title}`" @close="playWork = null" />
  </div>
</template>

<style scoped>
.works-page { display: flex; flex-direction: column; }
.cover-ph.playable { cursor: pointer; }
.cover-ph.no-output { opacity: .5; }
.no-output-tag { position: absolute; bottom: 6px; left: 6px; font-size: 11px; background: rgba(0,0,0,.45); color: #cbd0e0; padding: 1px 6px; border-radius: 6px; }
.work-card { padding: 10px 12px 12px; display: flex; flex-direction: column; gap: 6px; position: relative; }
.work-title { font-size: 13px; font-weight: 600; margin-top: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.work-meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.rendering-tag { position: absolute; left: 18px; top: 18px; background: rgba(0,0,0,.55); color: #35b8cc; font-size: 11px; padding: 1px 7px; border-radius: 999px; }
.output-path { font-size: 11px; max-width: 100%; }
.work-foot { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.published-at { margin-top: 2px; }
.cover-upload { display: flex; align-items: center; gap: 10px; }
.cover-thumb { width: 54px; height: 80px; border-radius: 8px; background: linear-gradient(160deg, var(--surface-2), var(--surface-3)); border: var(--border); display: flex; align-items: center; justify-content: center; color: var(--text-3); }
.form-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.check-line { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; cursor: pointer; }
.field-block { display: block; margin-bottom: 12px; }
.field-title { display: block; font-size: 12px; color: var(--text-2); margin-bottom: 5px; }
.title-row { display: flex; gap: 8px; align-items: center; }
.title-row .input { flex: 1; }
</style>
