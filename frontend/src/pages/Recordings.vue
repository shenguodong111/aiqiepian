<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import MediaPlayer from '../components/MediaPlayer.vue'
import { fmtDuration, fmtBytes, fmtTime, SESSION_STATUS, ANALYZE_STATUS } from '../utils/format'

const router = useRouter()
const active = ref([])
const history = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const loading = ref(true)
const rooms = ref([])
const manualModal = ref(false)
const manualRoomId = ref('')
const drawer = ref(null) // session
const drawerJobs = ref([])
const playSession = ref(null) // 正在播放原始录制的 session

function playRec(s) { playSession.value = s }

let pollTimer = null
let tickTimer = null

async function load(silent = false) {
  try {
    const [a, h] = await Promise.all([
      http.get('/recordings', { status: 'active' }, { silent }),
      http.get('/recordings', { status: 'history', page: page.value, size }, { silent }),
    ])
    active.value = a.list.map(s => ({ ...s, _base: s.recorded_sec || 0, _at: Date.now() }))
    history.value = h.list
    total.value = h.total
  } catch {
    if (!silent) toast.error('加载录制任务失败，请确认后端已启动')
  } finally {
    loading.value = false
  }
}

// 本地每秒走表（基于服务端基准 + 本地流逝时间估算，3s 轮询校正）
function tick() {
  for (const s of active.value) {
    const elapsed = (Date.now() - s._at) / 1000
    s.recorded_sec = Math.min(s.duration_sec, Math.floor(s._base + elapsed))
    s.file_size_bytes = Math.floor(s.recorded_sec * 600000)
  }
}

async function stop(s) {
  try {
    await http.post(`/recordings/${s.id}/stop`)
    toast.success('已停止录制，分析任务已入队')
    load(true)
  } catch { /* http 已 toast */ }
}

async function analyze(s) {
  try {
    await http.post(`/recordings/${s.id}/analyze`)
    toast.success('重新分析已入队')
    load(true)
  } catch { /* http 已 toast */ }
}

async function openManual() {
  manualModal.value = true
  try { rooms.value = (await http.get('/rooms', { size: 100 }, { silent: true })).list } catch { rooms.value = [] }
}
async function doManual() {
  if (!manualRoomId.value) return toast.error('请选择直播间')
  try {
    await http.post('/recordings', { roomId: Number(manualRoomId.value) })
    toast.success('手动录制会话已创建')
    manualModal.value = false
    manualRoomId.value = ''
    load(true)
  } catch { /* http 已 toast */ }
}

async function openDrawer(s) {
  drawer.value = s
  drawerJobs.value = []
  try {
    const d = await http.get('/jobs', { size: 200 }, { silent: true })
    drawerJobs.value = d.list.filter(j => j.entity_type === 'session' && j.entity_id === s.id)
  } catch { /* 忽略 */ }
}

const drawerClips = ref(0)
async function loadDrawerClips(id) {
  try {
    const d = await http.get('/clips', { sessionId: id, size: 1 }, { silent: true })
    drawerClips.value = d.total
  } catch { drawerClips.value = 0 }
}

function gotoClips(s) {
  router.push({ path: '/clips', query: { sessionId: s.id } })
}

onMounted(() => {
  load()
  tickTimer = setInterval(tick, 1000)
  pollTimer = setInterval(() => load(true), 3000)
})
onUnmounted(() => { clearInterval(pollTimer); clearInterval(tickTimer) })

// 抽屉打开时附带片段数
watch(drawer, (s) => { if (s) loadDrawerClips(s.id) })
</script>

<template>
  <div class="rec-page">
    <div class="section-head" style="margin-bottom: 0">
      <span class="card-title" style="font-size: 14px">进行中（{{ active.length }}）</span>
      <button class="btn primary" @click="openManual">● 手动录制</button>
    </div>

    <LoadingBlock v-if="loading" />
    <div v-else-if="!active.length" class="card" style="padding: 22px; margin-top: 10px">
      <div class="hint" style="text-align: center">当前没有进行中的录制。检测到开播后会自动出现在这里，也可点击右上角「手动录制」强制开会话。</div>
    </div>

    <div v-else class="active-grid" style="margin-top: 10px">
      <div v-for="s in active" :key="s.id" class="card active-card hover" @click="openDrawer(s)">
        <div class="active-head">
          <span class="dot rec"></span>
          <span class="active-room">{{ s.room_name || '房间#' + s.room_id }}</span>
          <span class="badge red">{{ SESSION_STATUS[s.status] || s.status }}</span>
        </div>
        <div class="active-nums">
          <div><span class="num big">{{ fmtDuration(s.recorded_sec) }}</span><span class="hint"> / {{ fmtDuration(s.duration_sec) }}</span></div>
          <div class="num muted">{{ fmtBytes(s.file_size_bytes) }}</div>
        </div>
        <div class="progress stripe"><i :style="{ width: Math.min(99, (s.recorded_sec / (s.duration_sec || 1)) * 100) + '%' }"></i></div>
        <div class="active-foot">
          <span class="hint">开始于 {{ fmtTime(s.started_at) }}</span>
          <span style="flex: 1"></span>
          <button class="btn sm danger" @click.stop="stop(s)">停止录制</button>
        </div>
      </div>
    </div>

    <div class="section-head" style="margin: 26px 0 10px">
      <span class="card-title" style="font-size: 14px">历史会话</span>
    </div>
    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!history.length" icon="🗂" text="暂无历史录制会话。等待直播间开播或手动录制一场，结束后会归档到这里。" action-label="● 手动录制" @action="openManual" />
    <div v-else class="card table-wrap">
      <table class="table">
        <thead>
          <tr><th>房间</th><th>开始</th><th>结束</th><th>时长</th><th>产物文件</th><th>分析状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in history" :key="s.id" style="cursor: pointer" @click="openDrawer(s)">
            <td>{{ s.room_name || '房间#' + s.room_id }}</td>
            <td class="num muted">{{ fmtTime(s.started_at) }}</td>
            <td class="num muted">{{ fmtTime(s.ended_at) }}</td>
            <td class="num">{{ fmtDuration(s.duration_sec) }}</td>
            <td><span class="trunc hint" :title="s.file_path">{{ s.file_path || '--' }}</span></td>
            <td>
              <span class="badge" :class="{ NONE: 'gray', PENDING: 'orange', RUNNING: 'cyan', DONE: 'green' }[s.analyze_status] || 'gray'">
                {{ ANALYZE_STATUS[s.analyze_status] || s.analyze_status }}
              </span>
            </td>
            <td @click.stop>
              <div class="row-actions">
                <button v-if="s.file_path" class="btn sm" @click="playRec(s)">▶ 播放</button>
                <button class="btn sm" :disabled="s.analyze_status === 'RUNNING'" @click="analyze(s)">重新分析</button>
                <button class="btn sm ghost" @click="gotoClips(s)">查看片段</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <Pagination :total="total" :page="page" :size="size" @update:page="p => { page = p; load(true) }" />
    </div>

    <!-- 手动录制 -->
    <Modal v-if="manualModal" title="手动录制" @close="manualModal = false">
      <p class="hint" style="margin-bottom: 14px">即使未检测到开播，也会为目标直播间强制创建录制会话（模拟一场 25–45 分钟的录制）。</p>
      <label class="field"><span>选择直播间 *</span>
        <select class="input" v-model="manualRoomId">
          <option value="" disabled>请选择…</option>
          <option v-for="r in rooms" :key="r.id" :value="r.id">{{ r.room_name || r.anchor_name || r.room_url }}</option>
        </select>
      </label>
      <p v-if="!rooms.length" class="hint">暂无直播间，请先到「直播间」页面添加。</p>
      <div class="modal-foot">
        <button class="btn ghost" @click="manualModal = false">取消</button>
        <button class="btn primary" :disabled="!rooms.length" @click="doManual">开始录制</button>
      </div>
    </Modal>

    <!-- 详情抽屉 -->
    <Teleport to="body">
      <div v-if="drawer" class="drawer-mask" @click.self="drawer = null">
        <div class="drawer">
          <h3>会话详情 #{{ drawer.id }}</h3>
          <div class="kv"><span class="hint">房间</span><span>{{ drawer.room_name || '房间#' + drawer.room_id }}</span></div>
          <div class="kv"><span class="hint">状态</span><span>{{ SESSION_STATUS[drawer.status] || drawer.status }}</span></div>
          <div class="kv"><span class="hint">文件</span><span class="trunc" :title="drawer.file_path">{{ drawer.file_path || '--' }}</span></div>
          <div class="kv"><span class="hint">体积</span><span class="num">{{ fmtBytes(drawer.file_size_bytes) }}</span></div>

          <div class="card-title" style="margin: 18px 0 10px; font-size: 13px">时间轴</div>
          <div class="timeline">
            <div class="tl-item done"><i></i><span>录制开始 {{ fmtTime(drawer.started_at) }}</span></div>
            <div class="tl-item" :class="{ done: drawer.status !== 'RECORDING' }"><i></i><span>录制结束 {{ fmtTime(drawer.ended_at) }}</span></div>
            <div class="tl-item" :class="{ done: drawer.analyze_status === 'DONE' }"><i></i><span>分析{{ ANALYZE_STATUS[drawer.analyze_status] || drawer.analyze_status }}</span></div>
            <div class="tl-item" :class="{ done: drawerClips > 0 }"><i></i><span>产出片段 {{ drawerClips }} 个</span></div>
          </div>

          <div class="card-title" style="margin: 18px 0 10px; font-size: 13px">关联任务日志</div>
          <div v-if="!drawerJobs.length" class="hint">暂无任务日志</div>
          <div v-else class="log-stream">
            <div v-for="j in drawerJobs" :key="j.id" class="log-line">
              <span class="badge" :class="{ SUCCESS: 'green', FAILED: 'red', RUNNING: 'cyan', PENDING: 'gray' }[j.status]">{{ j.job_type }}</span>
              <span>{{ j.log || '（等待执行）' }}</span>
              <span class="hint num">{{ fmtTime(j.started_at || j.created_at) }}</span>
            </div>
          </div>

          <div class="modal-foot">
            <button class="btn" @click="gotoClips(drawer)">查看关联片段 →</button>
            <button class="btn ghost" @click="drawer = null">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 原始录制播放 -->
    <MediaPlayer v-if="playSession" type="session" :id="playSession.id" :label="`录制回放 · ${playSession.room_name || ('会话 #' + playSession.id)}`" @close="playSession = null" />
  </div>
</template>

<style scoped>
.rec-page { display: flex; flex-direction: column; }
.active-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.active-card { padding: 16px 18px; display: flex; flex-direction: column; gap: 10px; cursor: pointer; }
.active-head { display: flex; align-items: center; gap: 8px; }
.active-room { font-weight: 600; font-size: 14px; }
.active-nums { display: flex; justify-content: space-between; align-items: baseline; }
.big { font-size: 20px; font-weight: 700; }
.active-foot { display: flex; align-items: center; }
.table-wrap { padding: 6px 14px 14px; overflow-x: auto; }
.kv { display: flex; gap: 12px; font-size: 13px; margin-bottom: 8px; }
.kv .hint { flex: none; width: 52px; }
.timeline { display: flex; flex-direction: column; gap: 0; margin-left: 5px; }
.tl-item { display: flex; align-items: center; gap: 12px; padding: 8px 0; position: relative; font-size: 13px; color: var(--text-3); }
.tl-item i { width: 10px; height: 10px; border-radius: 50%; background: rgba(255,255,255,.15); flex: none; transition: background .3s; }
.tl-item::before { content: ''; position: absolute; left: 4.5px; top: 22px; bottom: -6px; width: 1px; background: rgba(255,255,255,.1); }
.tl-item:last-child::before { display: none; }
.tl-item.done { color: var(--text); }
.tl-item.done i { background: linear-gradient(135deg, #0c8fa1, #00695f); box-shadow: 0 0 8px rgba(12, 143, 161, 0.6); }
.log-stream { max-height: 220px; overflow-y: auto; background: #0a0a0e; border: var(--border); border-radius: 10px; padding: 10px 12px; font-size: 12px; font-family: Consolas, monospace; }
.log-line { display: flex; align-items: flex-start; gap: 8px; padding: 4px 0; border-bottom: 1px dashed rgba(255,255,255,.05); color: var(--text-2); flex-wrap: wrap; }
.log-line .hint { margin-left: auto; }
</style>
