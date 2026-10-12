<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import { WORK_STATUS, fmtDuration, fmtTime, copyText } from '../utils/format'

const COMP_STATUS = {
  DRAFT: { label: '草稿', cls: 'gray' },
  RENDERING: { label: '生成中', cls: 'cyan' },
  READY: { label: '已就绪', cls: 'green' },
  FAILED: { label: '失败', cls: 'red' },
}

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(true)
const busyId = ref(null)
const deleting = ref(false)
const confirmDel = ref(null)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const d = await http.get('/compilations', { page: page.value, size }, { silent })
    list.value = d.list
    total.value = d.total
  } catch { if (!silent) list.value = [] } finally { loading.value = false }
}

const anyRendering = computed(() => list.value.some(c => c.status === 'RENDERING'))
let timer = null
onMounted(() => {
  load()
  // 仅在存在生成中的合集时轮询（后端假进度 0→100）
  timer = setInterval(() => { if (anyRendering.value) load(true) }, 2000)
})
onUnmounted(() => clearInterval(timer))

async function render(c) {
  busyId.value = c.id
  try {
    await http.post(`/compilations/${c.id}/render`)
    toast.success(`合集《${c.title}》开始生成`)
    load(true)
  } catch { /* http 已 toast */ } finally { busyId.value = null }
}

function askDel(c) { confirmDel.value = c }
async function doDel() {
  const c = confirmDel.value
  confirmDel.value = null
  try {
    await http.del(`/compilations/${c.id}`)
    toast.success('合集已删除')
    load(true)
  } catch { /* 生成中等错误已 toast */ }
}

async function copyPath(path) {
  (await copyText(path)) ? toast.success('产物路径已复制') : toast.error('复制失败')
}

// ---------- 新建合集 ----------
const modal = ref(null) // { title, workOptions: [...], selectedIds: [Ordered] }
const creating = ref(false)
const workPool = ref([])

async function openCreate() {
  modal.value = { title: '', workOptions: [], selectedIds: [] }
  try {
    const d = await http.get('/works', { size: 200 }, { silent: true })
    workPool.value = d.list.filter(w => ['READY', 'APPROVED', 'PUBLISHED'].includes(w.status))
    modal.value.workOptions = workPool.value
  } catch { workPool.value = []; modal.value.workOptions = [] }
}

function isChecked(id) { return modal.value.selectedIds.includes(id) }
function toggleWork(id) {
  const s = modal.value.selectedIds
  modal.value.selectedIds = isChecked(id) ? s.filter(x => x !== id) : [...s, id]
}
function move(id, dir) {
  const s = [...modal.value.selectedIds]
  const i = s.indexOf(id)
  const j = i + dir
  if (i < 0 || j < 0 || j >= s.length) return
  s.splice(i, 1); s.splice(j, 0, id)
  modal.value.selectedIds = s
}
function orderOf(id) { return modal.value.selectedIds.indexOf(id) + 1 }
const workById = id => workPool.value.find(w => w.id === id)

async function saveCreate() {
  const m = modal.value
  if (!m.title.trim()) return toast.error('请填写合集标题')
  if (!m.selectedIds.length) return toast.error('请至少勾选一个作品')
  creating.value = true
  try {
    await http.post('/compilations', { title: m.title.trim(), work_ids: m.selectedIds })
    toast.success('合集已创建，点击「生成合集」串联作品')
    modal.value = null
    page.value = 1
    load(true)
  } catch { /* http 已 toast */ } finally { creating.value = false }
}
</script>

<template>
  <div class="comp-page">
    <div class="filter-bar">
      <span class="hint">多个已产出成片的作品可按顺序串联为一支合集视频</span>
      <span style="flex: 1"></span>
      <button class="btn ghost" @click="load()">刷新</button>
      <button class="btn primary" @click="openCreate">＋ 新建合集</button>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!list.length" icon="🎞️"
      text="还没有合集。选择多个已完成/已通过/已发布的作品，将它们串联为一支长视频。"
      action-label="＋ 新建合集" @action="openCreate" />

    <template v-else>
      <div class="grid cols-2">
        <div v-for="c in list" :key="c.id" class="card comp-card">
          <div class="comp-head">
            <span class="comp-title trunc" :title="c.title">{{ c.title }}</span>
            <span class="badge" :class="COMP_STATUS[c.status]?.cls || 'gray'" style="margin-left:auto">
              {{ COMP_STATUS[c.status]?.label || c.status }}
            </span>
          </div>
          <div class="comp-meta">
            <span>{{ c.work_count }} 个作品</span><span class="sep">·</span>
            <span>总时长 {{ fmtDuration(c.total_duration_sec) }}</span><span class="sep">·</span>
            <span class="hint">创建于 {{ fmtTime(c.created_at) }}</span>
          </div>
          <div v-if="c.status === 'RENDERING'" class="progress" style="margin: 6px 0">
            <i :style="{ width: (c.progress || 0) + '%' }"></i>
          </div>
          <div v-if="c.status === 'FAILED' && c.error_msg" class="err-line trunc" :title="c.error_msg">失败：{{ c.error_msg }}</div>
          <div v-if="c.output_path" class="path-line">
            <span class="hint">产物</span>
            <span class="copyable trunc" :title="c.output_path + '（点击复制）'" @click="copyPath(c.output_path)">{{ c.output_path }}</span>
          </div>
          <div class="comp-foot">
            <span class="hint">{{ (c.work_ids || '').split(',').filter(Boolean).map(id => ('作品#' + id)).join(' → ') }}</span>
            <span style="flex: 1"></span>
            <div class="row-actions">
              <button v-if="c.status !== 'RENDERING'" class="btn sm" :class="{ primary: c.status !== 'READY' }"
                :disabled="busyId === c.id" @click="render(c)">
                {{ c.status === 'READY' ? '重新生成' : c.status === 'FAILED' ? '重试生成' : '生成合集' }}
              </button>
              <button v-else class="btn sm" disabled>生成中…</button>
              <button class="btn sm ghost danger" :disabled="c.status === 'RENDERING'" @click="askDel(c)">删除</button>
            </div>
          </div>
        </div>
      </div>
      <Pagination :total="total" :page="page" :size="size" @update:page="p => { page = p; load() }" />
    </template>

    <!-- 新建合集弹窗：勾选 READY/APPROVED/PUBLISHED 作品 + 上下调序 -->
    <Modal v-if="modal" title="新建合集" wide @close="modal = null">
      <label class="field"><span>合集标题 *</span>
        <input class="input" v-model="modal.title" maxlength="200" placeholder="如：2026 十月名场面合集" />
      </label>
      <div v-if="!modal.workOptions.length" class="hint" style="padding: 18px; text-align: center">
        暂无可入合集的作品（需 待送审 / 已通过 / 已发布 状态且有成片文件）
      </div>
      <div v-else class="work-pool">
        <div v-for="w in modal.workOptions" :key="w.id" class="work-row" :class="{ picked: isChecked(w.id) }">
          <label class="check-line">
            <input type="checkbox" :checked="isChecked(w.id)" @change="toggleWork(w.id)" />
            <span class="trunc" style="max-width: 320px" :title="w.title">{{ w.title }}</span>
          </label>
          <span class="badge" :class="WORK_STATUS[w.status]?.cls">{{ WORK_STATUS[w.status]?.label || w.status }}</span>
          <template v-if="isChecked(w.id)">
            <span class="order-badge">#{{ orderOf(w.id) }}</span>
            <span style="flex: 1"></span>
            <button class="btn sm ghost" :disabled="orderOf(w.id) === 1" title="上移" @click="move(w.id, -1)">↑</button>
            <button class="btn sm ghost" :disabled="orderOf(w.id) === modal.selectedIds.length" title="下移" @click="move(w.id, 1)">↓</button>
          </template>
        </div>
        <div class="hint" style="margin-top: 8px">已选 {{ modal.selectedIds.length }} 个，拼接顺序：{{ modal.selectedIds.join(' → ') || '—' }}</div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="modal = null">取消</button>
        <button class="btn primary" :disabled="creating" @click="saveCreate">{{ creating ? '创建中…' : '创建合集' }}</button>
      </div>
    </Modal>

    <!-- 删除确认 -->
    <Modal v-if="confirmDel" title="删除确认" @close="confirmDel = null">
      <p style="margin-bottom: 6px">确定删除合集「{{ confirmDel.title }}」？</p>
      <p class="hint">将同时移除已生成的合集视频文件（如有）；不影响成员作品。</p>
      <div class="modal-foot">
        <button class="btn ghost" @click="confirmDel = null">取消</button>
        <button class="btn danger" @click="doDel">确认删除</button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.comp-page { display: flex; flex-direction: column; gap: 4px; }
.comp-card { padding: 16px 18px; display: flex; flex-direction: column; gap: 8px; }
.comp-head { display: flex; align-items: center; gap: 8px; }
.comp-title { font-weight: 600; font-size: 14px; max-width: 70%; }
.comp-meta { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-2); }
.comp-meta .sep { color: var(--text-3); }
.err-line { font-size: 12px; color: #ff7a7a; }
.path-line { display: flex; gap: 8px; font-size: 12px; min-width: 0; }
.comp-foot { display: flex; align-items: center; gap: 10px; margin-top: 4px; padding-top: 10px; border-top: 1px solid rgba(255,255,255,.05); font-size: 12px; min-width: 0; }
.check-line { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; min-width: 0; }
.check-line input[type='checkbox'] { accent-color: var(--accent); width: 15px; height: 15px; flex: none; }
.work-pool { max-height: 360px; overflow-y: auto; border: var(--border); border-radius: 10px; padding: 6px; }
.work-row { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 8px; font-size: 13px; }
.work-row.picked { background: var(--accent-soft); }
.order-badge { font-size: 11.5px; font-weight: 700; color: var(--accent); background: rgba(12, 143, 161, 0.14); border-radius: 6px; padding: 1px 7px; }
</style>
