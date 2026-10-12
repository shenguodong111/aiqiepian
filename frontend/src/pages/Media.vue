<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import MediaPlayer from '../components/MediaPlayer.vue'
import { fmtBytes, fmtTime, copyText } from '../utils/format'

const router = useRouter()
const CATS = [
  { key: 'recordings', label: '录制原始' },
  { key: 'clips', label: '片段' },
  { key: 'subtitles', label: '字幕' },
  { key: 'covers', label: '封面' },
  { key: 'works', label: '成片' },
]
const cat = ref('recordings')
const files = ref([])
const total = ref(0)
const page = ref(1)
const size = 15
const loading = ref(true)
const stats = ref(null)
const cleanupModal = ref(false)
const cleaning = ref(false)

// 勾选 + 批量删除（按当前分类）
const selected = ref(new Set())
const delModal = ref(false)
const delForce = ref(false)
const deleting = ref(false)
const CAT_LABEL = { recordings: '原始录制', clips: '片段', subtitles: '字幕', covers: '封面', works: '成片' }
const selectedBytes = computed(() => files.value.reduce((s, f) => selected.value.has(f.ref?.id) ? s + (f.size_bytes || 0) : s, 0))
const allChecked = computed(() => files.value.length > 0 && files.value.every(f => f.ref && selected.value.has(f.ref.id)))
function toggleCheck(id) { const s = new Set(selected.value); s.has(id) ? s.delete(id) : s.add(id); selected.value = s }
function toggleAll() { selected.value = allChecked.value ? new Set() : new Set(files.value.filter(f => f.ref).map(f => f.ref.id)) }
function clearSelection() { selected.value = new Set() }
function askDelete() { if (!selected.value.size) return; delForce.value = false; delModal.value = true }
async function doDelete() {
  deleting.value = true
  try {
    const ids = [...selected.value]
    const d = await http.post('/media/delete', { category: cat.value, ids, force: delForce.value })
    const mb = d?.freed_bytes ? (d.freed_bytes / 1048576).toFixed(1) : '0'
    let msg = `已删除 ${d?.removed ?? 0} 项，回收 ${mb} MB`
    if (d?.removed_works) msg += `（含 ${d.removed_works} 个作品）`
    if (d?.skipped_published) msg += `；${d.skipped_published} 项因已发布被跳过（勾选"强制"可删本地记录）`
    toast.success(msg)
    delModal.value = false
    clearSelection()
    load(true); loadStats()
  } catch { /* http 已 toast */ } finally { deleting.value = false }
}

async function load(silent = false) {
  try {
    const d = await http.get('/media', { category: cat.value, page: page.value, size }, { silent })
    files.value = d.list
    total.value = d.total
  } catch { if (!silent) toast.error('加载素材失败') } finally { loading.value = false }
}
async function loadStats() {
  try { stats.value = await http.get('/media/stats', null, { silent: true }) } catch { stats.value = null }
}
function switchCat(k) { cat.value = k; page.value = 1; clearSelection(); load() }
function goPage(p) { page.value = p; clearSelection(); load(true) }

const statRows = computed(() => {
  if (!stats.value) return []
  const t = stats.value.total_bytes || 1
  return [
    { label: '录制原始', bytes: stats.value.recordings_bytes },
    { label: '成片', bytes: stats.value.works_bytes },
    { label: '字幕', bytes: stats.value.subtitles_bytes },
    { label: '封面', bytes: stats.value.covers_bytes },
  ].map(r => ({ ...r, pct: Math.round((r.bytes / t) * 100) }))
})

const REF_LINKS = { session: '/recordings', work: '/works', clip: '/clips' }
function gotoRef(ref_) {
  const link = REF_LINKS[ref_?.type]
  if (link) router.push({ path: link, query: ref_.type === 'session' ? { sessionId: ref_.id } : {} })
}

async function copyPath(p) { (await copyText(p)) ? toast.success('路径已复制') : toast.error('复制失败') }

// 按当前分类映射到 /api/media/stream 的资源类型
const playFile = ref(null) // { type, id, label }
const PLAY_TYPE = { recordings: 'session', works: 'work', covers: 'cover', clips: 'clip' }
function play(f) {
  const t = PLAY_TYPE[cat.value]
  if (!t || !f.ref) return toast.error('该分类文件暂不支持预览')
  playFile.value = { type: t, id: f.ref.id, label: f.name || '预览' }
}

async function openCleanup() { cleanupModal.value = true }
async function doCleanup() {
  cleaning.value = true
  try {
    const d = await http.post('/media/cleanup', { scope: 'published_recordings' })
    toast.success(`清理完成，已标记移除 ${d?.removed_sessions ?? 0} 个已发布成片的原始录制`)
  } catch { /* http 已 toast */ } finally {
    cleaning.value = false
    cleanupModal.value = false
    load(true)
    loadStats()
  }
}

onMounted(() => { load(); loadStats() })
</script>

<template>
  <div class="media-page">
    <!-- FR-P8-02 存储统计 -->
    <div class="card stats-card" v-if="stats">
      <div class="section-head">
        <span class="card-title">存储占用</span>
        <span class="hint num">media 根目录 · 合计 {{ fmtBytes(stats.total_bytes) }}</span>
      </div>
      <div class="stat-rows">
        <div v-for="r in statRows" :key="r.label" class="stat-row">
          <span class="stat-label">{{ r.label }}</span>
          <div class="progress"><i :style="{ width: r.pct + '%' }"></i></div>
          <span class="num stat-bytes">{{ fmtBytes(r.bytes) }}</span>
        </div>
      </div>
    </div>

    <div class="filter-bar" style="margin-top: 16px">
      <div class="tabs">
        <button v-for="c in CATS" :key="c.key" :class="{ active: cat === c.key }" @click="switchCat(c.key)">{{ c.label }}</button>
      </div>
      <span style="flex: 1"></span>
      <button class="btn danger" :disabled="!selected.size" @click="askDelete">删除所选（{{ selected.size }}{{ selected.size ? ' · ' + fmtBytes(selectedBytes) : '' }}）</button>
      <button class="btn danger ghost" @click="openCleanup">清理已发布成片的原始录制</button>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!files.length" icon="📁"
      :text="'该分类下暂无磁盘真实存在的文件。管线录制 / 成片 / 封面产出后会自动归档到这里。'" />

    <template v-else>
      <div class="card table-wrap">
        <table class="table">
          <thead><tr><th style="width: 34px"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></th><th>名称</th><th>大小</th><th>创建时间</th><th>路径</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(f, i) in files" :key="i">
              <td><input type="checkbox" :checked="f.ref && selected.has(f.ref.id)" :disabled="!f.ref" @change="toggleCheck(f.ref.id)" /></td>
              <td>{{ f.name || '--' }}</td>
              <td class="num muted">{{ fmtBytes(f.size_bytes) }}</td>
              <td class="num muted">{{ fmtTime(f.created_at) }}</td>
              <td><span class="copyable trunc hint" style="max-width: 320px" :title="f.path + '（点击复制）'" @click="copyPath(f.path)">{{ f.path }}</span></td>
              <td>
                <button v-if="PLAY_TYPE[cat] && f.ref" class="btn sm" @click="play(f)">▶ 预览</button>
                <button v-if="f.ref" class="link-btn" @click="gotoRef(f.ref)">{{ f.ref.type }}#{{ f.ref.id }} →</button>
                <span v-if="!f.ref" class="hint">—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <Pagination :total="total" :page="page" :size="size" @update:page="goPage" />
      </div>
    </template>

    <!-- 媒体播放/预览 -->
    <MediaPlayer v-if="playFile" :type="playFile.type" :id="playFile.id" :label="`预览 · ${playFile.label}`" @close="playFile = null" />

    <!-- 批量删除二次确认 -->
    <Modal v-if="delModal" title="删除确认" @close="delModal = false">
      <p style="margin-bottom: 8px">将删除所选 <b>{{ selected.size }}</b> 个<b>{{ CAT_LABEL[cat] }}</b>文件，回收约 <b class="num">{{ fmtBytes(selectedBytes) }}</b>。此操作不可恢复。</p>
      <p class="hint" style="margin-bottom: 10px">
        <template v-if="cat==='recordings'">删除原始录制文件，并级联删除该会话下的片段及其作品/审核/发布记录（录制任务、片段分析、剪辑工作台等同步消失）；含已发布作品的会话默认保留记录、仅删原始文件。</template>
        <template v-else-if="cat==='clips'">删除片段，并级联删除其生成的作品与成片/封面/字幕文件。</template>
        <template v-else-if="cat==='works'">删除作品，连带其成片/封面/字幕文件与审核/发布记录。</template>
        <template v-else-if="cat==='subtitles'">仅删除对应作品的字幕(.srt)源文件。</template>
        <template v-else-if="cat==='covers'">仅删除对应作品的封面图。</template>
      </p>
      <div v-if="cat==='clips' || cat==='works'" class="check-line" style="margin-bottom: 10px">
        <label class="switch"><input type="checkbox" v-model="delForce" /><span class="track"></span></label>
        <span>强制删除（含已发布作品，仅删本地记录，B 站稿件不受影响）</span>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="delModal = false">取消</button>
        <button class="btn danger" :disabled="deleting" @click="doDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
      </div>
    </Modal>

    <!-- 清理二次确认 -->
    <Modal v-if="cleanupModal" title="清理确认" @close="cleanupModal = false">
      <p style="margin-bottom: 10px">将清理以下<b>录制原始文件</b>（对应作品已发布的会话），本期仅标记删除并移除记录，不真实删除磁盘文件：</p>
      <div class="clean-list">
        <div v-if="!files.length" class="hint">当前分类无文件</div>
        <div v-for="(f, i) in files.slice(0, 8)" :key="i" class="clean-item">
          <span class="trunc">{{ f.path }}</span><span class="num hint">{{ fmtBytes(f.size_bytes) }}</span>
        </div>
        <div v-if="files.length > 8" class="hint">… 等共 {{ total }} 个文件</div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="cleanupModal = false">取消</button>
        <button class="btn danger" :disabled="cleaning" @click="doCleanup">{{ cleaning ? '清理中…' : '确认清理' }}</button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.media-page { display: flex; flex-direction: column; }
.stats-card { padding: 18px 22px; }
.stat-rows { display: flex; flex-direction: column; gap: 10px; }
.stat-row { display: flex; align-items: center; gap: 14px; }
.stat-label { width: 76px; font-size: 12.5px; color: var(--text-2); flex: none; }
.stat-row .progress { flex: 1; }
.stat-bytes { width: 90px; text-align: right; font-size: 12.5px; flex: none; }
.table-wrap { padding: 6px 14px 14px; overflow-x: auto; }
.clean-list { background: #0a0a0e; border: var(--border); border-radius: 10px; padding: 10px 14px; max-height: 200px; overflow-y: auto; font-size: 12px; }
.clean-item { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; border-bottom: 1px dashed rgba(255,255,255,.05); color: var(--text-2); }
.clean-item .trunc { max-width: 380px; }
</style>
