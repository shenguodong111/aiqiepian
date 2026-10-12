<script setup>
import { ref, computed, onMounted } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import { store, patchSettings } from '../stores/app'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Modal from '../components/Modal.vue'
import MediaPlayer from '../components/MediaPlayer.vue'
import { REVIEW_DECISION, WORK_STATUS, fmtTime } from '../utils/format'

const tab = ref('pending') // pending | approved | rejected | records
const pending = ref([])
const approved = ref([])
const rejected = ref([])
const records = ref([])
const reviewByWork = ref({})
const loading = ref(true)
const rejectModal = ref(null) // { work }
const rejectReason = ref('')
const rulesDrawer = ref(false)
const rules = ref({ auto_pass_score: 85, auto_reject_score: 70, auto_work_min_score: 70 })
const sensitiveWords = ref('')
const manualOnUncertain = ref(true)
const savingRules = ref(false)
const playWork = ref(null) // 正在预览成片的 work

function play(w) { playWork.value = w }

async function load(silent = false) {
  try {
    const [p, allWorks, rec] = await Promise.all([
      http.get('/review/pending', { size: 100 }, { silent }),
      http.get('/works', { size: 200 }, { silent: true }),
      http.get('/review/records', { size: 100 }, { silent: true }),
    ])
    pending.value = p.list
    approved.value = allWorks.list.filter(w => ['APPROVED', 'PUBLISHING', 'PUBLISHED'].includes(w.status))
    rejected.value = allWorks.list.filter(w => w.status === 'REJECTED')
    records.value = rec.list
    const m = {}
    for (const r of rec.list) if (!m[r.work_id]) m[r.work_id] = r
    reviewByWork.value = m
  } catch { if (!silent) toast.error('加载审核数据失败') } finally { loading.value = false }
}

function reviewByWorkTitle(id) {
  const all = [...pending.value, ...approved.value, ...rejected.value]
  return all.find(w => w.id === id)?.title || `作品 #${id}`
}

async function pass(w) {
  try {
    await http.post(`/review/${w.id}/decision`, { decision: 'pass' })
    toast.success('已放行，作品进入发布队列')
    load(true)
  } catch { /* http 已 toast */ }
}

function askReject(w) { rejectModal.value = { work: w }; rejectReason.value = '' }
async function doReject() {
  if (!rejectReason.value.trim()) return toast.error('拒绝必须填写原因')
  try {
    await http.post(`/review/${rejectModal.value.work.id}/decision`, { decision: 'reject', reason: rejectReason.value.trim() })
    toast.success('已拒绝该作品')
    rejectModal.value = null
    load(true)
  } catch { /* http 已 toast */ }
}

async function openRules() {
  rulesDrawer.value = true
  try {
    const [r, s] = await Promise.all([http.get('/review/rules', null, { silent: true }), http.get('/settings', null, { silent: true })])
    rules.value = r
    sensitiveWords.value = s.sensitive_words || ''
    manualOnUncertain.value = s.manual_on_uncertain ?? true
  } catch { /* 用默认值 */ }
}

async function saveRules() {
  if (rules.value.auto_reject_score >= rules.value.auto_pass_score) return toast.error('自动拒绝阈值需小于自动通过阈值')
  savingRules.value = true
  try {
    rules.value = await http.put('/review/rules', {
      auto_pass_score: rules.value.auto_pass_score,
      auto_reject_score: rules.value.auto_reject_score,
      auto_work_min_score: rules.value.auto_work_min_score,
    })
    await patchSettings({ sensitive_words: sensitiveWords.value, manual_on_uncertain: manualOnUncertain.value })
    toast.success('审核规则已保存并即时生效')
    rulesDrawer.value = false
  } catch { /* http 已 toast */ } finally { savingRules.value = false }
}

const currentList = computed(() => ({ pending: pending.value, approved: approved.value, rejected: rejected.value }[tab.value] || []))

onMounted(load)
</script>

<template>
  <div class="review-page">
    <div class="filter-bar">
      <div class="tabs">
        <button :class="{ active: tab === 'pending' }" @click="tab = 'pending'">待人工处理（{{ pending.length }}）</button>
        <button :class="{ active: tab === 'approved' }" @click="tab = 'approved'">已通过（{{ approved.length }}）</button>
        <button :class="{ active: tab === 'rejected' }" @click="tab = 'rejected'">已拒绝（{{ rejected.length }}）</button>
        <button :class="{ active: tab === 'records' }" @click="tab = 'records'">审计记录</button>
      </div>
      <span style="flex: 1"></span>
      <button class="btn" @click="openRules">⚙ 审核规则</button>
      <button class="btn ghost" @click="load()">刷新</button>
    </div>

    <LoadingBlock v-if="loading" />

    <!-- 审计记录页签 -->
    <div v-else-if="tab === 'records'" class="card table-wrap">
      <div v-if="!records.length" class="hint" style="padding: 30px; text-align: center">暂无审核决策记录</div>
      <table v-else class="table">
        <thead><tr><th>时间</th><th>作品</th><th>决策</th><th>置信度</th><th>理由</th><th>操作者</th></tr></thead>
        <tbody>
          <tr v-for="r in records" :key="r.id">
            <td class="num muted">{{ fmtTime(r.created_at) }}</td>
            <td class="trunc" style="max-width: 180px">{{ reviewByWorkTitle(r.work_id) }}</td>
            <td><span class="badge" :class="REVIEW_DECISION[r.decision]?.cls">{{ REVIEW_DECISION[r.decision]?.label || r.decision }}</span></td>
            <td class="num">{{ r.confidence }}%</td>
            <td class="muted" style="font-size: 12.5px">{{ r.reason }}</td>
            <td class="hint">{{ r.operator === 'system' ? '系统自动' : '本机人工' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <EmptyState v-else-if="!currentList.length" icon="🛡"
      :text="tab === 'pending' ? '当前没有待人工处理的作品。评分处于人工区间的成片会自动出现在这里。' : tab === 'approved' ? '还没有通过审核的作品。' : '没有被拒绝的作品，继续保持！'"
      :action-label="tab === 'pending' ? '前往剪辑工作台' : ''" @action="$router.push('/works')" />

    <!-- 作品卡列表 -->
    <div v-else class="grid cols-2">
      <div v-for="w in currentList" :key="w.id" class="card review-card">
        <button class="cover-ph" style="width: 86px; flex: none; cursor: pointer" @click="play(w)" title="播放成片">▶</button>
        <div class="review-body">
          <div class="review-title trunc" :title="w.title">{{ w.title }}</div>
          <div class="review-meta">
            <span class="badge" :class="WORK_STATUS[w.status]?.cls">{{ WORK_STATUS[w.status]?.label || w.status }}</span>
            <span class="hint num">评分 {{ w.score }}</span>
          </div>
          <div v-if="reviewByWork[w.id]" class="ai-judge">
            <span class="hint">AI 判定：</span>{{ reviewByWork[w.id].reason }}
            <span class="conf num">置信度 {{ reviewByWork[w.id].confidence }}%</span>
          </div>
        </div>
        <div class="review-actions" v-if="tab === 'pending'">
          <button class="btn primary" @click="pass(w)">放行</button>
          <button class="btn danger" @click="askReject(w)">拒绝</button>
        </div>
      </div>
    </div>

    <!-- 成片播放预览 -->
    <MediaPlayer v-if="playWork" type="work" :id="playWork.id" :label="`成片预览 · ${playWork.title}`" @close="playWork = null" />

    <!-- 拒绝原因 -->
    <Modal v-if="rejectModal" title="拒绝作品" @close="rejectModal = null">
      <p style="margin-bottom: 12px">拒绝「{{ rejectModal.work.title }}」</p>
      <label class="field"><span>拒绝原因 *（必填，将写入审计记录）</span>
        <textarea class="input" v-model="rejectReason" placeholder="例如：内容存在版权风险 / 剪辑节奏不佳"></textarea>
      </label>
      <div class="modal-foot">
        <button class="btn ghost" @click="rejectModal = null">取消</button>
        <button class="btn danger" @click="doReject">确认拒绝</button>
      </div>
    </Modal>

    <!-- 审核规则抽屉 -->
    <Teleport to="body">
      <div v-if="rulesDrawer" class="drawer-mask" @click.self="rulesDrawer = false">
        <div class="drawer">
          <h3>审核规则设置</h3>
          <label class="field"><span>自动通过阈值（评分 ≥，默认 85）</span>
            <input class="input num" type="number" min="0" max="100" v-model.number="rules.auto_pass_score" />
          </label>
          <label class="field"><span>自动拒绝阈值（评分 <，默认 70）</span>
            <input class="input num" type="number" min="0" max="100" v-model.number="rules.auto_reject_score" />
          </label>
          <label class="field"><span>自动成片最低分（片段评分 ≥ 自动生成作品）</span>
            <input class="input num" type="number" min="0" max="100" v-model.number="rules.auto_work_min_score" />
          </label>
          <label class="field"><span>敏感词黑名单（一行一词，仅存储；本期模拟判定不参与）</span>
            <textarea class="input" v-model="sensitiveWords" placeholder="低俗&#10;暴力"></textarea>
          </label>
          <label class="check-line" style="margin: 6px 0 4px">
            <label class="switch"><input type="checkbox" v-model="manualOnUncertain" /><span class="track"></span></label>
            <span>不确定项交人工处理</span>
          </label>
          <p class="hint" style="margin-top: 10px">人工区间 = [自动拒绝阈值, 自动通过阈值)，保存后即时生效。</p>
          <div class="modal-foot">
            <button class="btn ghost" @click="rulesDrawer = false">取消</button>
            <button class="btn primary" :disabled="savingRules" @click="saveRules">{{ savingRules ? '保存中…' : '保存规则' }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.review-page { display: flex; flex-direction: column; }
.table-wrap { padding: 6px 14px 14px; overflow-x: auto; }
.review-card { padding: 14px 16px; display: flex; gap: 14px; align-items: flex-start; }
.review-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.review-title { font-weight: 600; font-size: 13.5px; max-width: 100%; }
.review-meta { display: flex; align-items: center; gap: 10px; }
.ai-judge { font-size: 12px; color: var(--text-2); background: var(--surface-2); border: var(--border); border-radius: 9px; padding: 8px 10px; }
.conf { margin-left: 8px; color: #35b8cc; }
.review-actions { display: flex; flex-direction: column; gap: 8px; flex: none; }
.check-line { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font-size: 13px; }
.check-line input[type='checkbox'] { accent-color: var(--accent); }
</style>
