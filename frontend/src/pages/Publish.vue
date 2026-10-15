<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import { patchSettings, store } from '../stores/app'
import LoadingBlock from '../components/LoadingBlock.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import Modal from '../components/Modal.vue'
import { PUBLISH_STATUS, PLATFORMS, fmtTime } from '../utils/format'

const account = ref(null)
const accountLoaded = ref(false)
const cookieStatus = ref(null)
const bindModal = ref(false)
const bindNickname = ref('')

const tasks = ref([])
const tasksTotal = ref(0)
const tasksPage = ref(1)
const taskStatus = ref('')
const records = ref([])
const approvedWorks = ref([])
const loading = ref(true)
const autoPublish = ref(false)
const logModal = ref(null) // { task, lines }
const busyId = ref(null)
let timer = null

async function loadAccount() {
  try {
    account.value = await http.get('/publish/account', null, { silent: true })
  } catch { /* 忽略 */ } finally { accountLoaded.value = true }
  try {
    cookieStatus.value = await http.get('/publish/account/status', null, { silent: true })
  } catch { /* 忽略 */ }
}

// 登录态掉了的话，发布只会一条条失败——在这里直接说清原因并给去处，而不是让人猜
const cookieDead = computed(() => ['EXPIRED', 'MISSING'].includes(cookieStatus.value?.status))

async function load(silent = false) {
  try {
    const [t, r, s, aw] = await Promise.all([
      http.get('/publish/tasks', { status: taskStatus.value, page: tasksPage.value, size: 10 }, { silent }),
      http.get('/publish/records', { size: 20 }, { silent: true }),
      http.get('/settings', null, { silent: true }),
      http.get('/works', { status: 'APPROVED', size: 50 }, { silent: true }),
    ])
    tasks.value = t.list
    tasksTotal.value = t.total
    records.value = r.list
    autoPublish.value = !!s.auto_publish
    approvedWorks.value = aw.list
    store.connected = true
  } catch {
    if (!silent) toast.error('加载发布数据失败')
  } finally { loading.value = false }
}

const checking = ref(false)
async function checkCookie() {
  checking.value = true
  try {
    const r = await http.get('/publish/account/check')
    if (r.login) {
      // 后端校验通过时已落库绑定，直接用服务端返回的账号刷新（不再前端伪造，避免刷新后丢绑定态）
      account.value = r.account || { platform: 'bilibili', nickname: r.uname, bound_at: new Date().toISOString() }
      toast.success(`Cookie 有效，已绑定「${r.uname}」`)
    }
    else toast.error(`Cookie 无效或未配置：${r.message || '未登录'}`)
  } catch { /* http 已 toast */ } finally { checking.value = false }
}

async function doBind() {  try {
    account.value = await http.post('/publish/account/bind', { nickname: bindNickname.value.trim() || undefined })
    toast.success(`已绑定 ${PLATFORMS[account.value.platform]} 账号「${account.value.nickname}」`)
    bindModal.value = false
    load(true)
  } catch { /* http 已 toast */ }
}

async function unbind() {
  try {
    await http.del('/publish/account')
    account.value = null
    toast.success('发布账号已解绑，自动发布同时关闭')
    await patchSettings({ auto_publish: false }).catch(() => {})
    autoPublish.value = false
    load(true)
  } catch { /* http 已 toast */ }
}

async function toggleAutoPublish(v) {
  if (v && !account.value) {
    toast.error('请先绑定发布账号，才能开启自动发布')
    return
  }
  try {
    await patchSettings({ auto_publish: v })
    autoPublish.value = v
    toast.success(v ? '审核通过后将自动进入发布队列' : '自动发布已关闭，需手动发布')
  } catch { /* patchSettings 已回滚 */ }
}

async function retryTask(t) {
  busyId.value = t.id
  try {
    await http.post(`/publish/tasks/${t.id}/retry`)
    toast.success('发布任务已重新排队')
    load(true)
  } catch { /* http 已 toast */ } finally { busyId.value = null }
}

async function viewLog(t) {
  logModal.value = { task: t, lines: [] }
  try {
    const d = await http.get('/jobs', { type: 'PUBLISH', size: 100 }, { silent: true })
    logModal.value.lines = d.list
      .filter(j => j.entity_type === 'work' && j.entity_id === t.work_id)
      .map(j => `[${j.status}] ${j.log || ''} ${fmtTime(j.started_at || j.created_at)}`)
    if (t.error_msg) logModal.value.lines.unshift(`[FAILED] ${t.error_msg}`)
    if (!logModal.value.lines.length) logModal.value.lines.push('（暂无日志，任务处于排队阶段）')
  } catch {
    logModal.value.lines = [t.error_msg || '（日志获取失败）']
  }
}

async function publishWork(w) {
  busyId.value = w.id
  try {
    await http.post(`/works/${w.id}/publish`)
    toast.success('发布任务已创建')
    load(true)
  } catch { /* http 已 toast（含未绑定账号提示） */ } finally { busyId.value = null }
}

// M4-3 播放量同步：对已发布 BV 稿件回拉 view/like/danmaku（后端每 10 分钟也自动一轮）
const syncingViews = ref(false)
async function syncViews() {
  syncingViews.value = true
  try {
    const d = await http.post('/publish/views/sync')
    toast.success(`播放数据已同步：成功 ${d.synced} 条${d.failed ? `，失败 ${d.failed} 条` : ''}`)
    load(true)
  } catch { /* http 已 toast */ } finally { syncingViews.value = false }
}

onMounted(() => {
  loadAccount()
  load()
  timer = setInterval(() => load(true), 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="publish-page">
    <!-- 登录态掉了只会让每条发布都失败，先把原因和入口摆在这里 -->
    <div v-if="cookieDead" class="card cookie-dead">
      <b>B 站登录态{{ cookieStatus.status === 'MISSING' ? '未配置' : '已失效' }}</b>
      <span class="hint">—— {{ cookieStatus.message || '发布会被判为 -101 账号未登录' }}。</span>
      <router-link class="btn primary" style="margin-left:auto" to="/settings">去设置页扫码登录</router-link>
    </div>
    <!-- FR-P7-01 账号区 + FR-P7-03 自动发布 -->
    <div class="card acct-card">
      <div class="acct-left">
        <div class="card-title">发布账号</div>
        <div v-if="!accountLoaded" class="muted">读取中…</div>
        <div v-else-if="account" class="acct-bound">
          <span class="badge plat">B站</span>
          <div class="avatar">B</div>
          <div>
            <div style="font-weight: 600">{{ account.nickname }}</div>
            <div class="hint">绑定于 {{ fmtTime(account.bound_at) }}</div>
          </div>
          <button class="btn sm ghost danger" @click="unbind">解绑</button>
        </div>
        <div v-else class="acct-unbound">
          <span class="hint">尚未绑定 Bilibili 账号（mock 模式为模拟 OAuth；real 模式请在设置页填入 Cookie 后校验）</span>
          <div style="display:flex;gap:8px">
            <button class="btn sm ghost" :disabled="checking" @click="checkCookie">{{ checking ? '校验中…' : '校验 Cookie' }}</button>
            <button class="btn primary sm" @click="bindModal = true">绑定账号</button>
          </div>
        </div>
      </div>
      <div class="acct-right">
        <div class="auto-line">
          <div>
            <div style="font-size: 13px; font-weight: 600">自动发布</div>
            <div class="hint">审核通过后自动进入发布队列{{ account ? '' : '（需先绑定账号）' }}</div>
          </div>
          <label class="switch" :class="{ disabled: !account }">
            <input type="checkbox" :checked="autoPublish" :disabled="!account" @change="toggleAutoPublish($event.target.checked)" />
            <span class="track"></span>
          </label>
        </div>
        <!-- 稿件为什么发出去是"仅自己可见"，答案就在这个设置上，别让人再去设置页翻 -->
        <div class="auto-line vis-line">
          <div>
            <div style="font-size: 13px; font-weight: 600">稿件可见范围</div>
            <div class="hint">只影响此后新发布的稿件，已发布出去的不变</div>
          </div>
          <span class="badge" :class="store.settings?.publish_visibility === 'public' ? 'orange' : 'cyan'">
            {{ store.settings?.publish_visibility === 'public' ? '公开（所有人可见）' : '仅自己可见' }}
          </span>
          <router-link class="btn sm ghost" to="/settings">去修改</router-link>
        </div>
      </div>
    </div>

    <!-- 已通过待发布 -->
    <div v-if="approvedWorks.length" class="card strip">
      <span class="hint" style="flex: none">已通过 · 待发布：</span>
      <span v-for="w in approvedWorks.slice(0, 4)" :key="w.id" class="strip-item trunc" :title="w.title">{{ w.title }}</span>
      <span style="flex: 1"></span>
      <button class="btn sm" :disabled="busyId === approvedWorks[0]?.id" @click="publishWork(approvedWorks[0])">发布最新一个 →</button>
    </div>

    <!-- FR-P7-02 发布队列 -->
    <div class="section-head" style="margin: 20px 0 10px">
      <span class="card-title" style="font-size: 14px">发布队列</span>
      <div class="tabs">
        <button :class="{ active: taskStatus === '' }" @click="taskStatus = ''; tasksPage = 1; load(true)">全部</button>
        <button v-for="(v, k) in PUBLISH_STATUS" :key="k" :class="{ active: taskStatus === k }" @click="taskStatus = k; tasksPage = 1; load(true)">{{ v.label }}</button>
      </div>
    </div>

    <LoadingBlock v-if="loading" />
    <EmptyState v-else-if="!tasks.length" icon="🚀"
      text="发布队列为空。作品审核通过后会自动入队（需开启自动发布并绑定账号），也可在剪辑工作台手动点「发布」。"
      action-label="前往剪辑工作台" @action="$router.push('/works')" />

    <div v-else class="card table-wrap">
      <table class="table">
        <thead><tr><th>作品</th><th>平台</th><th>状态</th><th style="width: 140px">进度</th><th>失败原因</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td class="trunc" style="max-width: 220px">{{ t.work_title }}</td>
            <td><span class="badge plat">{{ PLATFORMS[t.platform] || t.platform }}</span></td>
            <td><span class="badge" :class="PUBLISH_STATUS[t.status]?.cls">{{ PUBLISH_STATUS[t.status]?.label || t.status }}</span></td>
            <td>
              <div class="progress" :class="{ stripe: t.status === 'UPLOADING' }"><i :style="{ width: t.progress + '%' }"></i></div>
            </td>
            <td class="hint trunc" style="max-width: 180px" :title="t.error_msg">{{ t.error_msg || '—' }}</td>
            <td>
              <div class="row-actions">
                <button v-if="t.status === 'FAILED'" class="btn sm" :disabled="busyId === t.id" @click="retryTask(t)">重试</button>
                <button class="btn sm ghost" @click="viewLog(t)">查看日志</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <Pagination :total="tasksTotal" :page="tasksPage" :size="10" @update:page="p => { tasksPage = p; load(true) }" />
    </div>

    <!-- FR-P7-05 发布记录 -->
    <div class="section-head" style="margin: 24px 0 10px">
      <span class="card-title" style="font-size: 14px">发布记录</span>
      <span style="flex: 1"></span>
      <button class="btn sm ghost" :disabled="syncingViews || !records.length" :title="`从 B 站公开接口回拉播放/点赞/弹幕数据`" @click="syncViews">
        {{ syncingViews ? '同步中…' : '同步数据' }}
      </button>
    </div>
    <div class="card table-wrap">
      <div v-if="!records.length" class="hint" style="padding: 26px; text-align: center">暂无成功发布的记录</div>
      <table v-else class="table">
        <thead><tr><th>标题</th><th>稿件 ID</th><th>发布时间</th><th class="num">播放</th><th class="num">点赞</th><th class="num">弹幕</th><th>数据同步时间</th></tr></thead>
        <tbody>
          <tr v-for="r in records" :key="r.id">
            <td class="trunc" style="max-width: 260px">{{ r.work_title }}</td>
            <td><span class="badge green num">{{ r.remote_id }}</span></td>
            <td class="num muted">{{ fmtTime(r.published_at) }}</td>
            <td class="num">{{ r.view_count == null ? '—' : r.view_count }}</td>
            <td class="num">{{ r.like_count == null ? '—' : r.like_count }}</td>
            <td class="num">{{ r.danmaku_count == null ? '—' : r.danmaku_count }}</td>
            <td class="num muted">{{ r.view_synced_at ? fmtTime(r.view_synced_at) : '未同步' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 绑定弹窗（模拟 OAuth） -->
    <Modal v-if="bindModal" title="绑定 Bilibili 账号（模拟 OAuth）" @close="bindModal = false">
      <p class="hint" style="margin-bottom: 14px">本期为模拟授权：点击确认后即以昵称完成绑定，M3 阶段接入真实 OAuth。</p>
      <label class="field"><span>授权昵称</span><input class="input" v-model="bindNickname" placeholder="留空则使用「AI切片演示号」" /></label>
      <div class="modal-foot">
        <button class="btn ghost" @click="bindModal = false">取消</button>
        <button class="btn primary" @click="doBind">确认授权</button>
      </div>
    </Modal>

    <!-- 日志弹窗 -->
    <Modal v-if="logModal" :title="'发布日志 · ' + logModal.task.work_title" wide @close="logModal = null">
      <div class="log-stream">
        <div v-for="(line, i) in logModal.lines" :key="i" class="log-line">{{ line }}</div>
      </div>
      <div class="modal-foot"><button class="btn ghost" @click="logModal = null">关闭</button></div>
    </Modal>
  </div>
</template>

<style scoped>
.publish-page { display: flex; flex-direction: column; }
.cookie-dead { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 18px; margin-bottom: 12px; border-left: 3px solid #ef5b5b; }
.acct-card { padding: 16px 20px; display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.acct-left { display: flex; flex-direction: column; gap: 10px; min-width: 260px; }
.acct-bound { display: flex; align-items: center; gap: 12px; }
.acct-unbound { display: flex; align-items: center; gap: 12px; }
.avatar {
  width: 38px; height: 38px; border-radius: 50%; background: linear-gradient(135deg, #0c8fa1, #00695f);
  display: flex; align-items: center; justify-content: center; font-weight: 700; flex: none;
}
.acct-right { min-width: 240px; }
.auto-line { display: flex; align-items: center; justify-content: space-between; gap: 16px; background: var(--surface-2); border: var(--border); border-radius: 11px; padding: 12px 16px; }
.vis-line { margin-top: 10px; }
.strip { margin-top: 12px; padding: 10px 16px; display: flex; align-items: center; gap: 12px; }
.strip-item { background: var(--surface-2); border: var(--border); border-radius: 999px; padding: 3px 12px; font-size: 12px; color: var(--text-2); max-width: 200px; }
.table-wrap { padding: 6px 14px 14px; overflow-x: auto; }
.log-stream { background: #0a0a0e; border: var(--border); border-radius: 10px; padding: 12px 14px; max-height: 320px; overflow-y: auto; font-size: 12px; font-family: Consolas, monospace; color: var(--text-2); }
.log-line { padding: 4px 0; border-bottom: 1px dashed rgba(255, 255, 255, 0.05); }
</style>
