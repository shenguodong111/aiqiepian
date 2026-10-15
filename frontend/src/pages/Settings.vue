<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import { store, loadSettings } from '../stores/app'
import { fmtBytes } from '../utils/format'
import LoadingBlock from '../components/LoadingBlock.vue'
import Modal from '../components/Modal.vue'

const loading = ref(true)
const saving = ref(false)
const resetModal = ref(false)
const version = ref('v1.0.0-M0')
// 总开关的唯一操作入口在侧栏底部；这里只读同一个 store 值显示状态，
// 避免同一页出现两个"看起来都能拨"的开关，也避免保存表单时把别处刚改的值覆盖回去。
const autoRun = computed(() => store.settings?.auto_run !== false)

const cleanModal = ref(false)
const cleaning = ref(false)
const cleanResult = ref(null)
const clean = reactive({ keep_per_session: 3, delete_files: true, purge_orphans: true })

async function doCleanRedundant() {
  cleaning.value = true
  cleanResult.value = null
  try {
    const r = await http.post('/media/cleanup-redundant', {
      keep_per_session: clean.keep_per_session,
      delete_files: clean.delete_files,
      purge_orphans: clean.purge_orphans,
    })
    cleanResult.value = r
    toast.success(`清理完成：删片段 ${r.removed_clips} · 作品 ${r.removed_works} · 孤儿文件 ${r.removed_orphans}${r.skipped_published ? ` · 保留已发布 ${r.skipped_published}` : ''}`)
    loadSettings(true)
  } catch { /* http 已 toast */ } finally {
    cleaning.value = false
  }
}

const form = reactive({
  // auto_run 故意不进这个表单：它是即时生效的全局开关，直接读写 store（与侧栏底部同一个），
  // 否则"拨了没保存"或"保存时把别处刚改的值覆盖回去"都会让两处显示不一致。
  monitor_interval_sec: 60,
  max_concurrent_record: 3,
  max_concurrent_analyze: 2,
  max_concurrent_render: 2,
  max_concurrent_heavy: 2,
  max_retry: 3,
  ai_mode: 'mock',
  ai_local_url: '',
  ai_local_model: '',
  ai_agent_cmd: '',
  ai_cloud_url: '',
  ai_cloud_model: '',
  ai_cloud_key: '',
  asr_enabled: true,
  reel_enabled: true,
  clip_dedup_enabled: true,
  review_before_publish: true,
  asr_model: 'small',
  asr_language: 'zh',
  asr_python: '',
  danmu_python: '',
  media_dir: './media',
  keep_days: 30,
  publish_default_tags: '',
  publish_desc_template: '',
  bili_sessdata: '',
  bili_jct: '',
  bili_uid: '',
  bili_browser_cookie: '',
  publish_tid: 244,
  max_record_minutes: 240,
})
const keyMasked = ref('')

const AI_MODES = [
  { key: 'local', label: '本地模型', desc: '本机 Ollama / OpenAI 兼容大模型：高光重排、弹幕摘要、投稿文案', ready: true },
  { key: 'agent', label: 'Agent 工具', desc: '后端尚未实现，选中不会生效', ready: false },
  { key: 'cloud', label: '云端 API', desc: '后端尚未实现，选中不会生效', ready: false },
]

async function load() {
  try {
    const s = await http.get('/settings')
    Object.keys(form).forEach(k => { if (s[k] !== undefined && s[k] !== null) form[k] = s[k] })
    // agent/cloud 后端未实现，历史上可能被存过：统一回落到内置规则
    if (form.ai_mode !== 'local') form.ai_mode = ''
    keyMasked.value = s.ai_cloud_key ? `****${String(s.ai_cloud_key).slice(-4)}` : ''
    const sys = await http.get('/dashboard/summary', null, { silent: true })
    if (sys?.system?.engine_version) version.value = sys.system.engine_version
  } catch { /* http 已 toast */ } finally { loading.value = false }
}

async function save() {
  if (form.monitor_interval_sec < 5) return toast.error('监控轮询间隔不得低于 5 秒')
  if (form.keep_days < 1) return toast.error('保留天数不得低于 1 天')
  saving.value = true
  const body = { ...form, ai_mode: form.ai_mode || 'mock' }
  // Key 留空或仍为掩码时不覆盖已保存值
  if (!body.ai_cloud_key || (keyMasked.value && body.ai_cloud_key === keyMasked.value)) delete body.ai_cloud_key
  try {
    await http.put('/settings', body)
    await loadSettings(true)
    toast.success('设置已保存，即时生效')
    load()
  } catch { /* http 已 toast */ } finally { saving.value = false }
}

async function resetDemo() {
  resetModal.value = false
  try {
    await http.post('/settings/reset-demo')
    toast.success('演示数据已重置，管线将从全新状态开始')
  } catch { /* http 已 toast */ }
}

/* ---- 跨机迁移：导出/导入配置 JSON ---- */
const exporting = ref(false)
const includeHistory = ref(false)
const importModal = ref(false)
const importing = ref(false)
const importPayload = ref(null)
const importInfo = ref(null)
const importResult = ref(null)

async function doExport() {
  exporting.value = true
  try {
    const data = await http.get('/config/export', { include_history: includeHistory.value ? 'true' : undefined })
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `aiqiepian-配置-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
    toast.success('已导出配置 JSON，把它拷到另一台电脑导入即可（不含 B 站 Cookie）')
  } catch { /* http 已 toast */ } finally { exporting.value = false }
}

function pickImport(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  file.text().then(txt => {
    let data
    try { data = JSON.parse(txt) } catch { return toast.error('这个文件不是合法的 JSON') }
    if (data?.kind !== 'aiqiepian-config') return toast.error('文件格式不对：请选择本工具导出的配置 JSON')
    importPayload.value = data
    importInfo.value = {
      exported_at: String(data.exported_at || '').slice(0, 19).replace('T', ' '),
      rooms: (data.rooms || []).length,
      history: data.history ? Object.values(data.history).reduce((n, arr) => n + (arr?.length || 0), 0) : 0,
    }
    importResult.value = null
    importModal.value = true
  })
}

async function doImport() {
  importing.value = true
  try {
    importResult.value = await http.post('/config/import', importPayload.value)
    toast.success('导入完成，直播间与设置已更新')
    loadSettings(true)
  } catch { /* http 已 toast */ } finally { importing.value = false }
}

// ---------- B 站登录态：扫码登录 / 自动续期 ----------
const cookieStatus = ref(null)
const qr = reactive({ open: false, image: '', key: '', url: '', status: '', message: '' })
let qrTimer = null

const COOKIE_TEXT = { VALID: '登录态有效', EXPIRED: '已过期·需重新扫码', MISSING: '未配置', UNKNOWN: '未体检', MOCK: '模拟引擎' }
function cookieText(s) { return COOKIE_TEXT[s?.status || 'UNKNOWN'] || '未体检' }

async function loadCookieStatus() {
  try { cookieStatus.value = await http.get('/publish/account/status', null, { silent: true }) } catch { /* 状态拉不到不影响设置页其它功能 */ }
}

async function startQrLogin() {
  stopQrPoll()
  const r = await http.post('/publish/account/qrcode', {})
  if (!r || !r.ok) { toast.error((r && r.message) || '当前引擎不支持扫码登录'); return }
  qr.open = true
  qr.image = r.image
  qr.key = r.qrcode_key
  qr.url = r.url
  qr.status = 'waiting'
  qr.message = '请用哔哩哔哩手机 App 扫下面的二维码'
  qrTimer = setInterval(pollQr, 2000)
}

async function pollQr() {
  if (!qr.key) return
  const r = await http.get('/publish/account/qrcode/poll', { key: qr.key }, { silent: true })
  if (!r) return
  qr.status = r.status
  qr.message = r.message || ''
  if (r.status === 'confirmed') {
    stopQrPoll()
    qr.open = false
    toast.success(`已登录「${r.nickname || 'B站账号'}」，Cookie 已自动存到本机`)
    await Promise.all([loadCookieStatus(), load()])
  } else if (r.status === 'expired' || r.status === 'canceled') {
    stopQrPoll()
  }
}

function stopQrPoll() {
  if (qrTimer) { clearInterval(qrTimer); qrTimer = null }
}

async function refreshCookie() {
  const r = await http.post('/publish/account/refresh', {})
  if (r && r.ok) toast.success(r.message || '已续期')
  else toast.error((r && r.message) || '续期失败，请扫码重新登录')
  await loadCookieStatus()
}

onMounted(() => { load(); loadCookieStatus() })
onUnmounted(stopQrPoll)
</script>

<template>
  <div class="settings-page">
    <LoadingBlock v-if="loading" />
    <template v-else>
      <div class="settings-grid">
        <!-- 管线 -->
        <div class="card group">
          <div class="group-title">管线 <span class="hint" style="font-weight: 400">六环节调度参数</span></div>
          <div class="check-line top-line">
            <span class="badge" :class="autoRun ? 'green' : 'orange'">{{ autoRun ? '运行中' : '已暂停' }}</span>
            <span>自动运行总开关<span class="hint" style="font-weight: 400"> · 这里只显示状态，开关在左侧栏底部（关闭后不再产生新任务，拨动即刻生效）</span></span>
          </div>
          <div class="form-2col">
            <label class="field"><span>监控轮询间隔（秒）</span><input class="input num" type="number" min="5" v-model.number="form.monitor_interval_sec" /></label>
            <label class="field"><span>失败自动重试次数</span><input class="input num" type="number" min="0" v-model.number="form.max_retry" /></label>
            <label class="field"><span>最大并发录制数</span><input class="input num" type="number" min="1" v-model.number="form.max_concurrent_record" /></label>
            <label class="field"><span>最大并发分析数</span><input class="input num" type="number" min="1" v-model.number="form.max_concurrent_analyze" /></label>
            <label class="field"><span>最大并发渲染数</span><input class="input num" type="number" min="1" v-model.number="form.max_concurrent_render" /></label>
            <label class="field"><span>真实重活并发（分析/成片后台线程）</span><input class="input num" type="number" min="1" v-model.number="form.max_concurrent_heavy" /></label>
          </div>
        </div>

        <!-- AI 接入 -->
        <div class="card group">
          <div class="group-title">AI 接入 <span class="badge cyan" style="margin-left: 8px">{{ form.ai_mode === 'local' ? '本地大模型' : '内置规则引擎' }}</span></div>
          <div class="radio-row">
            <label v-for="m in AI_MODES" :key="m.key" class="radio-card"
              :class="{ active: form.ai_mode === m.key, disabled: !m.ready }">
              <input type="radio" :value="m.key" v-model="form.ai_mode" :disabled="!m.ready" />
              <div>
                <div style="font-weight: 600; font-size: 13px">{{ m.label }}<span v-if="!m.ready" class="badge">后端未实现</span></div>
                <div class="hint" style="font-size: 11.5px">{{ m.desc }}</div>
              </div>
            </label>
          </div>
          <template v-if="form.ai_mode === 'local'">
            <label class="field"><span>推理服务 URL</span><input class="input" v-model="form.ai_local_url" placeholder="http://127.0.0.1:11434" /></label>
            <label class="field"><span>模型名称</span><input class="input" v-model="form.ai_local_model" placeholder="qwen2.5:3b" /></label>
            <p class="hint">需本机已启动 Ollama 并拉好该模型；连不上时程序不会报错中断，会自动退回内置规则模板生成标题/简介。</p>
          </template>
          <p v-else class="hint">不接大模型也能跑：标题用「房间名 + 战队 VS + 时刻」或高频弹幕汇总规则生成，简介用设置里的模板填充。想升级为本地大模型精修，选上方「本地模型」并填 URL。</p>
        </div>

        <!-- 语音字幕 ASR -->
        <div class="card group">
          <div class="group-title">语音字幕（本地 ASR） <span class="badge cyan" style="margin-left: 8px">{{ form.asr_enabled ? '已开启' : '已关闭' }}</span></div>
          <label class="check-line top-line">
            <label class="switch"><input type="checkbox" v-model="form.asr_enabled" /><span class="track"></span></label>
            <span>成片时自动识别语音并烧录字幕（真实引擎生效；无语音则只保留标题条）</span>
          </label>
          <p class="hint">基于本机 faster-whisper 离线转写，无需联网。B 站 / 斗鱼 / 虎牙录制的语音均可识别。</p>
          <div class="form-2col">
            <label class="field"><span>识别模型</span><input class="input" v-model="form.asr_model" placeholder="small" /></label>
            <label class="field"><span>语言</span><input class="input" v-model="form.asr_language" placeholder="zh" /></label>
          </div>
          <label class="field"><span>Python 解释器（可选）</span><input class="input" v-model="form.asr_python" placeholder="留空 = 自动探测 PATH 上的 python / py" /></label>
          <p class="hint">需要本机装有 faster-whisper。留空时程序会自己试 python、py 两个命令；装在别处就填完整的 python.exe 路径。</p>
        </div>

        <!-- 斗鱼弹幕通道 -->
        <div class="card group">
          <div class="group-title">斗鱼弹幕通道 <span class="badge cyan" style="margin-left: 8px">自动</span></div>
          <p class="hint">斗鱼起录时会自动实时采集弹幕，用来给候选片段算热度、填「弹幕条数 / 每分钟 / 峰值 / 发言人数 / 高频弹幕」并在 AI 理由里指出"弹幕互动活跃"。需要本机 Python 装一个包：<code>python -m pip install websocket-client</code>。</p>
          <label class="field"><span>Python 解释器（可选）</span><input class="input" v-model="form.danmu_python" placeholder="留空 = 复用上面的 ASR 解释器，再留空则自动探测 PATH 上的 python / py" /></label>
          <p class="hint">采不到就静默降级（日志写一句原因），录制、分析、成片照常跑，选段退回"语音 + 画面"判定。B 站弹幕仍走登录 Cookie，虎牙暂不采集。</p>
        </div>

        <!-- 高光集锦自动成片 -->
        <div class="card group">
          <div class="group-title">自动高光集锦 <span class="badge cyan" style="margin-left: 8px">{{ form.reel_enabled ? '已开启' : '已关闭' }}</span></div>
          <label class="check-line top-line">
            <label class="switch"><input type="checkbox" v-model="form.reel_enabled" /><span class="track"></span></label>
            <span>每次录制分析完成后，自动精选片段拼接成一支高光成片（真实引擎生效）</span>
          </label>
          <p class="hint">成片时长随录制总时长自适应（约录制时长的 1/3，最短 5 分钟、最长 20 分钟）。无弹幕或弹幕稀薄的直播间会优先用语音/画面信号选段。产物出现在剪辑工作台。</p>
        </div>

        <!-- 发布前人工审片 -->
        <div class="card group">
          <div class="group-title">发布前人工审片 <span class="badge cyan" style="margin-left: 8px">{{ form.review_before_publish ? '每支都要过目' : '机审通过即自动发布' }}</span></div>
          <label class="check-line top-line">
            <label class="switch"><input type="checkbox" v-model="form.review_before_publish" /><span class="track"></span></label>
            <span>开启：机审通过的作品停在「待审片」，由你在剪辑工作台改标题/封面后点「通过并发布」</span>
          </label>
          <p class="hint">
            关闭后，机审有把握（AUTO_PASS）的作品会<b>直接进入发布队列</b>，整条「监控 → 录制 → 分析 → 成片 → 上传」无人值守跑完；
            机审拿不准的仍然留给人工。前提是已绑定 B 站账号且自动发布开启——投稿默认<b>仅自己可见</b>，需要公开请到 B 站创作中心改。
          </p>
        </div>

        <!-- 轮播/重复内容去重 -->
        <div class="card group">
          <div class="group-title">轮播内容去重 <span class="badge cyan" style="margin-left: 8px">{{ form.clip_dedup_enabled ? '已开启' : '已关闭' }}</span></div>
          <label class="check-line top-line">
            <label class="switch"><input type="checkbox" v-model="form.clip_dedup_enabled" /><span class="track"></span></label>
            <span>对每个候选片段算画面指纹，与同房间历史片段比对，重复内容不再成片（真实引擎生效）</span>
          </label>
          <p class="hint">斗鱼等平台的「轮播」房会不停重播同一段录播内容。开启后照常录制，但循环重播的<b>同一片段只会被剪一次</b>，不会重复出片；轮播里不同的内容仍会正常切片。关闭则每次循环都会重新出片。比对会把重播带来的时间差对齐后再判定（同一内容从不同时刻录起也认得出）；已经攒下的重复片段可在「片段分析」页点「整理重复片段」一次清掉。</p>
        </div>

        <!-- 存储 -->
        <div class="card group">
          <div class="group-title">存储</div>
          <label class="field"><span>media 根目录</span><input class="input" v-model="form.media_dir" placeholder="./media" /></label>
          <label class="field"><span>保留天数（到期模拟清理）</span><input class="input num" type="number" min="1" v-model.number="form.keep_days" /></label>
          <p class="hint">单机版数据保存在本机磁盘，路径相对于程序运行目录。</p>
          <button class="btn" style="margin-top: 12px" @click="cleanModal = true">🧹 一键清理冗余片段 / 作品</button>
        </div>

        <!-- 跨机迁移 -->
        <div class="card group">
          <div class="group-title">跨机迁移 <span class="hint" style="font-weight: 400">换电脑时搬直播间和设置</span></div>
          <p class="hint">导出的是一份 JSON 文本文件，拷到 U 盘 / 传微信都行，在新电脑的设置页导入即可还原。
            <b>B 站 Cookie 属于登录凭证，永远不会被写进导出文件</b>，新机器需要重新粘贴一次。</p>
          <label class="check-line" style="margin: 10px 0 4px">
            <label class="switch"><input type="checkbox" v-model="includeHistory" /><span class="track"></span></label>
            <span>包含录制历史（会话 / 片段 / 作品记录；视频文件本身不在导出内）</span>
          </label>
          <div style="display: flex; gap: 10px; flex-wrap: wrap; margin-top: 10px">
            <button class="btn primary" :disabled="exporting" @click="doExport">{{ exporting ? '导出中…' : '导出配置 JSON' }}</button>
            <button class="btn" @click="$refs.importFileInput.click()">选择配置文件导入…</button>
          </div>
          <input ref="importFileInput" type="file" accept=".json,application/json" style="display: none" @change="pickImport" />
        </div>

        <!-- 发布 -->
        <div class="card group">
          <div class="group-title">发布</div>
          <label class="field"><span>默认标签（逗号分隔）</span><input class="input" v-model="form.publish_default_tags" placeholder="直播切片,AI剪辑" /></label>
          <label class="field"><span>投稿分区 ID（tid）</span><input class="input" type="number" v-model.number="form.publish_tid" placeholder="244" /></label>
          <div class="bili-auth">
            <div class="bili-auth-row">
              <span class="hint">B 站登录态</span>
              <span :class="['cookie-badge', (cookieStatus?.status || 'UNKNOWN').toLowerCase()]">{{ cookieText(cookieStatus) }}</span>
              <span class="hint num" v-if="cookieStatus?.checked_at">{{ String(cookieStatus.checked_at).slice(0, 16).replace('T', ' ') }} 体检</span>
            </div>
            <div class="hint" v-if="cookieStatus?.message">{{ cookieStatus.message }}</div>
            <div class="bili-auth-actions">
              <button class="btn primary" @click="startQrLogin">扫码登录</button>
              <button class="btn" @click="refreshCookie">尝试续期</button>
            </div>
            <div class="hint" style="margin-bottom: 8px">
              扫码即可，不用再去 F12 复制 Cookie。程序每 30 分钟自动体检，登录态能续就自己续；
              只有被 B 站强制作废（改密码 / 异地登录 / 风控）才需要重新扫一次码。下面三件套留作备用手工入口。
            </div>
          </div>
          <label class="field"><span>SESSDATA</span><input class="input" v-model="form.bili_sessdata" placeholder="扫码登录后自动写入并掩码显示，重新粘贴可覆盖" /></label>
          <label class="field"><span>bili_jct（csrf）</span><input class="input" v-model="form.bili_jct" /></label>
          <label class="field"><span>DedeUserID</span><input class="input" v-model="form.bili_uid" /></label>
          <div class="hint" style="margin: 8px 0 4px">（可选·弹幕采集需要）完整浏览器 Cookie：在 live.bilibili.com 按 F12 → 网络 → 任意 api.live.bilibili.com 请求 → 复制请求头里完整的 Cookie 粘贴到此</div>
          <label class="field"><span>完整 Cookie</span><textarea class="input" rows="2" v-model="form.bili_browser_cookie" placeholder="填了它，弹幕增强通道可绕过 B 站风控；留空则仅用上方三件套"></textarea></label>
          <label class="field"><span>默认简介模板（支持 {房间} {日期} 变量）</span>
            <textarea class="input" v-model="form.publish_desc_template"></textarea>
          </label>
        </div>

        <!-- 关于 -->
        <div class="card group">
          <div class="group-title">关于</div>
          <div class="kv"><span class="hint">版本</span><span>{{ version }}</span></div>
          <div class="kv"><span class="hint">数据目录</span><span class="num">{{ form.media_dir }}</span></div>
          <div class="kv"><span class="hint">架构</span><span>Vue3 + Vite 前端 · Spring Boot / mock-server 后端</span></div>
          <div class="kv"><span class="hint">全局状态</span><span>
            <span class="dot" :class="store.connected ? 'on' : 'off'"></span>
            {{ store.connected ? '后端连接正常' : '后端未连接' }}
          </span></div>
          <button class="btn danger" style="margin-top: 14px" @click="resetModal = true">重置演示数据</button>
        </div>
      </div>

      <div class="save-bar card">
        <span class="hint">修改后需点击保存后生效；「自动运行总开关」的唯一开关在左侧栏底部，本页只显示它的状态。</span>
        <button class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存全部设置' }}</button>
      </div>
    </template>

    <Modal v-if="resetModal" title="重置演示数据" @close="resetModal = false">
      <p style="margin-bottom: 8px">此操作将清空所有业务数据：</p>
      <ul class="reset-list">
        <li>直播间、录制会话、候选片段</li>
        <li>作品、审核记录、发布任务与记录</li>
        <li>任务队列与已绑定的发布账号</li>
      </ul>
      <p class="hint">设置项将保留。确定继续？</p>
      <div class="modal-foot">
        <button class="btn ghost" @click="resetModal = false">取消</button>
        <button class="btn danger" @click="resetDemo">确认重置</button>
      </div>
    </Modal>

    <Modal v-if="importModal" title="导入配置" @close="!importing && (importModal = false)">
      <template v-if="!importResult">
        <p style="margin-bottom: 8px">这份配置来自 <b class="num">{{ importInfo?.exported_at || '未知时间' }}</b>，将执行：</p>
        <ul class="reset-list">
          <li><b>{{ importInfo?.rooms || 0 }}</b> 个直播间：同 URL 的覆盖配置，新的直接添加（本机已有的其它房间不受影响）</li>
          <li>设置项按文件内容更新；<b>B 站 Cookie 不在文件里，会保持本机现有值</b></li>
          <li v-if="importInfo?.history">附带 <b class="num">{{ importInfo.history }}</b> 条历史记录（会话/片段/作品），已存在的自动跳过</li>
        </ul>
        <p class="hint">{{ importInfo?.history ? '注意：历史记录里的视频文件不在这台电脑上，播放会提示找不到文件。' : '只搬配置，不搬录制历史。' }}</p>
        <div class="modal-foot">
          <button class="btn ghost" @click="importModal = false">取消</button>
          <button class="btn primary" :disabled="importing" @click="doImport">{{ importing ? '导入中…' : '确认导入' }}</button>
        </div>
      </template>
      <template v-else>
        <p style="margin-bottom: 8px">导入完成：</p>
        <ul class="reset-list">
          <li>直播间 新增 {{ importResult.rooms_created }} · 覆盖 {{ importResult.rooms_updated }}</li>
          <li>设置项更新 {{ importResult.settings_updated }} 条</li>
          <li v-if="importResult.recordings_created !== undefined">
            历史：会话 {{ importResult.recordings_created }}（跳过 {{ importResult.recordings_skipped }}）·
            片段 {{ importResult.clips_created }}（跳过 {{ importResult.clips_skipped }}）·
            作品 {{ importResult.works_created }}（跳过 {{ importResult.works_skipped }}）
          </li>
        </ul>
        <div class="modal-foot">
          <button class="btn primary" @click="importModal = false">知道了</button>
        </div>
      </template>
    </Modal>

    <Modal v-if="cleanModal" title="一键清理冗余" @close="!cleaning && (cleanModal = false)">
      <p class="hint" style="margin-bottom: 12px">
        每个录制会话<b>只保留评分最高的前 N 个片段</b>，多余的片段连同其作品、审核/发布记录一并删除；可顺带清掉磁盘上数据库已不引用的孤儿文件。
        <b>已发布的作品、你配置的直播间/房间一律保留，不会动。</b>
      </p>
      <label class="field"><span>每会话保留片段数（N）</span><input class="input num" type="number" min="1" v-model.number="clean.keep_per_session" /></label>
      <label class="check-line" style="margin: 8px 0">
        <label class="switch"><input type="checkbox" v-model="clean.delete_files" /><span class="track"></span></label>
        <span>同时删除磁盘文件（成片/封面）</span>
      </label>
      <label class="check-line" style="margin: 0 0 12px">
        <label class="switch"><input type="checkbox" v-model="clean.purge_orphans" /><span class="track"></span></label>
        <span>扫描并删除孤儿文件（未被任何记录引用的 media 文件）</span>
      </label>
      <div v-if="cleanResult" class="hint" style="background: var(--surface-2); border: var(--border); border-radius: 10px; padding: 10px 12px; margin-bottom: 10px">
        已删片段 {{ cleanResult.removed_clips }} · 作品 {{ cleanResult.removed_works }} · 孤儿文件 {{ cleanResult.removed_orphans }} · 释放 {{ fmtBytes(cleanResult.freed_bytes || 0) }}<template v-if="cleanResult.skipped_published"> · 保留已发布 {{ cleanResult.skipped_published }}</template>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" :disabled="cleaning" @click="cleanModal = false">{{ cleanResult ? '关闭' : '取消' }}</button>
        <button v-if="!cleanResult" class="btn danger" :disabled="cleaning" @click="doCleanRedundant">{{ cleaning ? '清理中…' : '开始清理' }}</button>
      </div>
    </Modal>
    <Modal v-if="qr.open" title="扫码登录 B 站" @close="qr.open = false; stopQrPoll()">
      <div class="qr-box">
        <img v-if="qr.image" :src="qr.image" alt="B站登录二维码" class="qr-img" />
        <p v-else class="hint">二维码图片生成失败，可复制此链接在手机里打开登录：<span class="num">{{ qr.url }}</span></p>
        <div class="qr-status">{{ qr.message }}</div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="qr.open = false; stopQrPoll()">关闭</button>
        <button v-if="qr.status === 'expired' || qr.status === 'canceled'" class="btn primary" @click="startQrLogin">重新获取二维码</button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.settings-page { display: flex; flex-direction: column; gap: 16px; }
.bili-auth { margin: 12px 0 10px; padding: 10px 12px; border: 1px solid var(--line, #2a2f3a); border-radius: 8px; }
.bili-auth-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 4px; }
.bili-auth-actions { display: flex; gap: 8px; margin: 8px 0; }
.cookie-badge { font-size: 12px; font-weight: 700; padding: 2px 9px; border-radius: 999px; background: #333a47; color: #cfd6e4; }
.cookie-badge.valid { background: rgba(52, 199, 123, .16); color: #4ade80; }
.cookie-badge.expired { background: rgba(239, 91, 91, .16); color: #f87171; }
.cookie-badge.missing { background: rgba(245, 179, 66, .16); color: #fbbf24; }
.qr-box { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 6px 0 2px; }
.qr-img { width: 240px; height: 240px; image-rendering: pixelated; background: #fff; border-radius: 8px; padding: 6px; }
.qr-status { font-size: 13px; color: var(--text-dim, #9aa4b2); }
.settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 16px; align-items: start; }
.group { padding: 18px 22px; }
.group-title { font-size: 14px; font-weight: 700; margin-bottom: 14px; display: flex; align-items: center; }
.form-2col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.check-line { display: inline-flex; align-items: center; gap: 10px; cursor: pointer; font-size: 13px; }
.top-line { margin-bottom: 14px; }
.radio-row { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.radio-card {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 11px;
  background: var(--surface-2); border: var(--border); cursor: pointer; transition: all 0.16s;
}
.radio-card:hover { border-color: var(--border-strong); }
.radio-card.active { border-color: rgba(12, 143, 161, 0.6); background: var(--accent-soft); }
.radio-card input { accent-color: var(--accent); }
.radio-card.disabled { opacity: 0.5; cursor: not-allowed; }
.radio-card .badge { margin-left: 6px; font-size: 10.5px; vertical-align: 1px; }
.kv { display: flex; gap: 12px; font-size: 13px; margin-bottom: 8px; align-items: center; }
.kv .hint { flex: none; width: 76px; }
.kv .dot { margin-right: 4px; }
.save-bar { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; position: sticky; bottom: 0; backdrop-filter: blur(10px); }
.reset-list { margin: 0 0 10px 18px; font-size: 13px; color: var(--text-2); }
</style>
