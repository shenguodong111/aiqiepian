<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import { PLATFORMS, ROOM_STATUS, ANALYZE_TEMPLATES, relTime, copyText } from '../utils/format'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = 12
const loading = ref(true)
const filters = reactive({ platform: '', status: '', kw: '' })
const selecting = ref(false)
const checked = ref(new Set())
const saving = ref(false)

const modal = ref(null) // { room|null, form }
const confirmDel = ref(null)

const URL_RULES = {
  bilibili: { re: /live\.bilibili\.com\/\d+/i, hint: 'https://live.bilibili.com/房间号' },
  douyu: { re: /douyu\.com\/\d+|douyu\.com\/profile/i, hint: 'https://www.douyu.com/房间号' },
  huya: { re: /huya\.com\/[\w-]+/i, hint: 'https://www.huya.com/主播名' },
  douyin: { re: /douyin\.com|live\.douyin/i, hint: 'https://live.douyin.com/房间号' },
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const d = await http.get('/rooms', { platform: filters.platform, status: filters.status, kw: filters.kw, page: page.value, size }, { silent })
    list.value = d.list
    total.value = d.total
  } catch { if (!silent) list.value = [] } finally { loading.value = false }
}

function applyFilters() { page.value = 1; load() }
function resetFilters() { filters.platform = ''; filters.status = ''; filters.kw = ''; applyFilters() }

function openAdd() {
  modal.value = {
    room: null,
    form: { platform: 'bilibili', room_url: '', room_name: '', anchor_name: '', monitor_enabled: true, auto_record: true, auto_publish: true, analyze_template: 'highlight' },
  }
}
function openEdit(room) {
  modal.value = { room, form: { ...room } }
}

function validateUrl(form) {
  const rule = URL_RULES[form.platform]
  if (!rule) return true
  if (!rule.re.test(form.room_url || '')) return `URL 需匹配 ${PLATFORMS[form.platform]} 格式，例如 ${rule.hint}`
  return true
}

async function saveModal() {
  const f = modal.value.form
  if (!f.platform) return toast.error('请选择平台')
  if (!f.room_url) return toast.error('请填写直播间 URL')
  const v = validateUrl(f)
  if (v !== true) return toast.error(v)
  saving.value = true
  try {
    const body = {
      platform: f.platform, room_url: f.room_url, room_name: f.room_name, anchor_name: f.anchor_name,
      monitor_enabled: f.monitor_enabled, auto_record: f.auto_record, auto_publish: f.auto_publish ?? true, analyze_template: f.analyze_template,
    }
    if (modal.value.room) {
      await http.put(`/rooms/${modal.value.room.id}`, body)
      toast.success('直播间已更新')
    } else {
      await http.post('/rooms', body)
      toast.success('直播间已添加，监控将自动开始')
    }
    modal.value = null
    load(true)
  } catch { /* http 已 toast */ } finally { saving.value = false }
}

async function toggleRoom(room, enabled) {
  try {
    await http.post(`/rooms/${room.id}/toggle`, { enabled })
    toast.success(enabled ? `已开启「${room.room_name || room.anchor_name}」监控` : `已暂停监控`)
    load(true)
  } catch { load(true) }
}

// 房间级自动发布开关（仅影响审核通过后是否自动入队，人工点发布不受限）
async function toggleAutoPublish(room, enabled) {
  try {
    await http.put(`/rooms/${room.id}`, { auto_publish: enabled })
    toast.success(enabled ? `「${room.room_name || room.anchor_name}」审核通过后将自动发布` : `「${room.room_name || room.anchor_name}」自动发布已关闭`)
    load(true)
  } catch { load(true) }
}

async function checkRoom(room) {
  try {
    const d = await http.post(`/rooms/${room.id}/check`)
    const name = room.room_name || room.anchor_name
    if (d.live && d.note) toast.info(`「${name}」${d.note}`)
    else if (d.live) toast.success(`「${name}」已开播，录制会话已创建`)
    else toast.info(`「${name}」当前未开播`)
    load(true)
  } catch { /* http 已 toast */ }
}

function askDel(room) { confirmDel.value = room }
async function doDel() {
  const room = confirmDel.value
  confirmDel.value = null
  try {
    await http.del(`/rooms/${room.id}`)
    toast.success('直播间已删除')
    load(true)
  } catch { /* 录制中禁止删除等错误已由 http 弹出 */ }
}

async function copyUrl(url) {
  (await copyText(url)) ? toast.success('URL 已复制') : toast.error('复制失败')
}

// 批量
const allChecked = computed(() => list.value.length > 0 && list.value.every(r => checked.value.has(r.id)))
function toggleCheck(id) {
  const s = new Set(checked.value)
  s.has(id) ? s.delete(id) : s.add(id)
  checked.value = s
}
function toggleAll() {
  checked.value = allChecked.value ? new Set() : new Set(list.value.map(r => r.id))
}
async function batchSet(enabled) {
  const ids = [...checked.value]
  if (!ids.length) return toast.error('请先勾选直播间')
  try {
    for (const id of ids) await http.post(`/rooms/${id}/toggle`, { enabled }, { silent: true })
    toast.success(`已批量${enabled ? '开启' : '暂停'} ${ids.length} 个直播间监控`)
    checked.value = new Set()
    selecting.value = false
    load(true)
  } catch { load(true) }
}

onMounted(load)
</script>

<template>
  <div class="rooms-page">
    <div class="filter-bar">
      <select class="input" v-model="filters.platform" @change="applyFilters">
        <option value="">全部平台</option>
        <option v-for="(label, k) in PLATFORMS" :key="k" :value="k">{{ label }}</option>
      </select>
      <select class="input" v-model="filters.status" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="MONITORING">监控中</option>
        <option value="RECORDING">录制中</option>
        <option value="OFF">未监控</option>
      </select>
      <input class="input" v-model="filters.kw" placeholder="搜索房间名 / 主播 / URL" @keyup.enter="applyFilters" style="min-width: 200px" />
      <button class="btn ghost" @click="resetFilters">重置</button>
      <span style="flex: 1"></span>
      <button class="btn" :class="{ primary: selecting }" @click="selecting = !selecting; checked = new Set()">
        {{ selecting ? '退出批量' : '批量操作' }}
      </button>
      <button class="btn primary" @click="openAdd">＋ 新增直播间</button>
    </div>

    <div v-if="selecting && list.length" class="batch-bar card">
      <label class="check-line">
        <input type="checkbox" :checked="allChecked" @change="toggleAll" />
        <span>全选本页（已选 {{ checked.size }} 个）</span>
      </label>
      <span style="flex: 1"></span>
      <button class="btn sm" :disabled="!checked.size" @click="batchSet(true)">批量开启监控</button>
      <button class="btn sm danger" :disabled="!checked.size" @click="batchSet(false)">批量暂停监控</button>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!list.length" icon="📡"
      text="还没有监控中的直播间。添加你的第一个直播间，开播即自动录制、AI 自动切片。"
      action-label="＋ 新增直播间" @action="openAdd" />

    <template v-else>
      <div class="grid cols-3">
        <div v-for="r in list" :key="r.id" class="card room-card hover" :class="{ checked: checked.has(r.id) }">
          <div class="room-head">
            <label v-if="selecting" class="check-line">
              <input type="checkbox" :checked="checked.has(r.id)" @change="toggleCheck(r.id)" />
            </label>
            <span class="badge plat">{{ PLATFORMS[r.platform] || r.platform }}</span>
            <span class="room-name trunc" :title="r.room_name || r.anchor_name">{{ r.room_name || r.anchor_name || '未命名直播间' }}</span>
            <span class="badge" :class="ROOM_STATUS[r.status]?.cls || 'gray'" style="margin-left:auto">
              <i class="dot" :class="ROOM_STATUS[r.status]?.cls || 'off'"></i>{{ ROOM_STATUS[r.status]?.label || r.status }}
            </span>
          </div>
          <div class="room-meta">
            <div class="meta-row"><span class="hint">主播</span><span>{{ r.anchor_name || '--' }}</span></div>
            <div v-if="r.live_title" class="meta-row"><span class="hint">直播标题</span><span class="trunc" :title="r.live_title">{{ r.live_title }}</span></div>
            <div class="meta-row"><span class="hint">URL</span>
              <span class="copyable trunc" @click="copyUrl(r.room_url)" :title="r.room_url + '（点击复制）'">{{ r.room_url }}</span>
            </div>
            <div class="meta-row"><span class="hint">模板</span><span>{{ ANALYZE_TEMPLATES[r.analyze_template] || r.analyze_template }}</span></div>
            <div class="meta-row"><span class="hint">最近开播</span><span>{{ relTime(r.last_live_at) }}</span></div>
          </div>
          <div class="room-foot">
            <label class="switch">
              <input type="checkbox" :checked="r.monitor_enabled" @change="toggleRoom(r, $event.target.checked)" />
              <span class="track"></span>
            </label>
            <span class="foot-label">监控</span>
            <label class="switch" style="margin-left: 10px" :title="`审核通过后是否自动进入发布队列`">
              <input type="checkbox" :checked="r.auto_publish !== false" @change="toggleAutoPublish(r, $event.target.checked)" />
              <span class="track"></span>
            </label>
            <span class="foot-label">自动发布</span>
            <span style="flex: 1"></span>
            <div class="row-actions">
              <button class="btn sm" @click="checkRoom(r)">立即检查</button>
              <button class="btn sm ghost" @click="openEdit(r)">编辑</button>
              <button class="btn sm ghost danger" @click="askDel(r)">删除</button>
            </div>
          </div>
        </div>
      </div>
      <Pagination :total="total" :page="page" :size="size" @update:page="p => { page = p; load() }" />
    </template>

    <!-- 新增/编辑弹窗 -->
    <Modal v-if="modal" :title="modal.room ? '编辑直播间' : '新增直播间'" @close="modal = null">
      <label class="field"><span>平台 *</span>
        <select class="input" v-model="modal.form.platform">
          <option v-for="(label, k) in PLATFORMS" :key="k" :value="k">{{ label }}</option>
        </select>
      </label>
      <label class="field"><span>直播间 URL *</span>
        <input class="input" v-model="modal.form.room_url" :placeholder="URL_RULES[modal.form.platform]?.hint" />
      </label>
      <div class="form-2col">
        <label class="field"><span>房间备注名</span><input class="input" v-model="modal.form.room_name" placeholder="用于拼接作品标题，不会被直播标题覆盖" /><span class="hint" style="font-size:11px">AI 标题以此名称为准；开播检测到的真实标题只显示不覆盖</span></label>
        <label class="field"><span>主播名</span><input class="input" v-model="modal.form.anchor_name" /></label>
      </div>
      <label class="field"><span>分析模板</span>
        <select class="input" v-model="modal.form.analyze_template">
          <option v-for="(label, k) in ANALYZE_TEMPLATES" :key="k" :value="k">{{ label }}</option>
        </select>
      </label>
      <div class="switch-lines">
        <label class="check-line"><label class="switch"><input type="checkbox" v-model="modal.form.monitor_enabled" /><span class="track"></span></label><span>开启监控（默认开）</span></label>
        <label class="check-line"><label class="switch"><input type="checkbox" v-model="modal.form.auto_record" /><span class="track"></span></label><span>开播自动录制（默认开）</span></label>
        <label class="check-line"><label class="switch"><input type="checkbox" v-model="modal.form.auto_publish" /><span class="track"></span></label><span>审核通过自动发布（默认开，关闭后需人工点发布）</span></label>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="modal = null">取消</button>
        <button class="btn primary" :disabled="saving" @click="saveModal">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </Modal>

    <!-- 删除二次确认 -->
    <Modal v-if="confirmDel" title="删除确认" @close="confirmDel = null">
      <p style="margin-bottom: 6px">确定删除直播间「{{ confirmDel.room_name || confirmDel.anchor_name || confirmDel.room_url }}」？</p>
      <p class="hint">录制中的直播间无法删除；删除不影响已产生的会话与作品。</p>
      <div class="modal-foot">
        <button class="btn ghost" @click="confirmDel = null">取消</button>
        <button class="btn danger" @click="doDel">确认删除</button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.rooms-page { display: flex; flex-direction: column; gap: 4px; }
.batch-bar { display: flex; align-items: center; gap: 10px; padding: 10px 16px; margin-bottom: 12px; font-size: 13px; }
.room-card { padding: 16px 18px; display: flex; flex-direction: column; gap: 10px; }
.room-card.checked { border-color: rgba(12, 143, 161, 0.55); }
.room-head { display: flex; align-items: center; gap: 8px; }
.room-name { font-weight: 600; font-size: 14px; max-width: 120px; }
.room-meta { display: flex; flex-direction: column; gap: 5px; font-size: 12.5px; }
.meta-row { display: flex; gap: 10px; min-width: 0; }
.meta-row .hint { flex: none; width: 56px; }
.meta-row > span:last-child { min-width: 0; }
.room-foot { display: flex; align-items: center; margin-top: 4px; padding-top: 10px; border-top: 1px solid rgba(255,255,255,.05); }
.foot-label { font-size: 11.5px; color: var(--text-3); margin-left: 6px; }
.check-line { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
.check-line input[type='checkbox'] { accent-color: var(--accent); width: 15px; height: 15px; }
.form-2col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.switch-lines { display: flex; flex-direction: column; gap: 10px; margin-top: 6px; }
.badge .dot { width: 6px; height: 6px; animation: none; }
</style>
