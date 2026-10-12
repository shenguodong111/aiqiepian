<script setup>
import { ref, reactive, onMounted } from 'vue'
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
  auto_run: true,
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
  asr_model: 'small',
  asr_language: 'zh',
  asr_python: '',
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
  { key: 'local', label: '本地模型', desc: '本机 Ollama / OpenAI 兼容大模型：高光重排、弹幕摘要、投稿文案' },
  { key: 'agent', label: 'Agent 工具', desc: '通过本地 Agent CLI 调用分析能力' },
  { key: 'cloud', label: '云端 API', desc: 'OpenAI 兼容的云端推理接口' },
]

async function load() {
  try {
    const s = await http.get('/settings')
    Object.keys(form).forEach(k => { if (s[k] !== undefined && s[k] !== null) form[k] = s[k] })
    if (form.ai_mode === 'mock') form.ai_mode = ''
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

onMounted(load)
</script>

<template>
  <div class="settings-page">
    <LoadingBlock v-if="loading" />
    <template v-else>
      <div class="settings-grid">
        <!-- 管线 -->
        <div class="card group">
          <div class="group-title">管线 <span class="hint" style="font-weight: 400">六环节调度参数</span></div>
          <label class="check-line top-line">
            <label class="switch"><input type="checkbox" v-model="form.auto_run" /><span class="track"></span></label>
            <span>自动运行总开关（关闭后不再产生新任务）</span>
          </label>
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
          <div class="group-title">AI 接入 <span class="badge cyan" style="margin-left: 8px">{{ form.ai_mode === 'local' ? '本地大模型' : form.ai_mode === 'agent' ? 'Agent 工具' : form.ai_mode === 'cloud' ? '云端 API' : '模拟执行中' }}</span></div>
          <div class="radio-row">
            <label v-for="m in AI_MODES" :key="m.key" class="radio-card" :class="{ active: form.ai_mode === m.key }">
              <input type="radio" :value="m.key" v-model="form.ai_mode" />
              <div>
                <div style="font-weight: 600; font-size: 13px">{{ m.label }}</div>
                <div class="hint" style="font-size: 11.5px">{{ m.desc }}</div>
              </div>
            </label>
          </div>
          <template v-if="form.ai_mode === 'local'">
            <label class="field"><span>推理服务 URL</span><input class="input" v-model="form.ai_local_url" placeholder="http://127.0.0.1:11434" /></label>
            <label class="field"><span>模型名称</span><input class="input" v-model="form.ai_local_model" placeholder="qwen2.5:3b" /></label>
          </template>
          <template v-else-if="form.ai_mode === 'agent'">
            <label class="field"><span>Agent 命令 / 入口</span><input class="input" v-model="form.ai_agent_cmd" placeholder="agent-cli analyze --session" /></label>
          </template>
          <template v-else-if="form.ai_mode === 'cloud'">
            <label class="field"><span>API URL</span><input class="input" v-model="form.ai_cloud_url" placeholder="https://api.example.com/v1" /></label>
            <label class="field"><span>模型名</span><input class="input" v-model="form.ai_cloud_model" placeholder="gemini-2.0-flash" /></label>
            <label class="field"><span>API Key（不回显明文）</span>
              <input class="input" type="password" v-model="form.ai_cloud_key" :placeholder="keyMasked ? `已保存（${keyMasked}），输入将覆盖` : 'sk-…'" />
            </label>
          </template>
          <p v-else class="hint">本期由 Mock 引擎驱动管线，M1–M3 起接入真实实现；选择模式仅保存配置。</p>
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
          <label class="field"><span>Python 路径（含 faster-whisper 解释器）</span><input class="input" v-model="form.asr_python" placeholder="python" /></label>
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

        <!-- 存储 -->
        <div class="card group">
          <div class="group-title">存储</div>
          <label class="field"><span>media 根目录</span><input class="input" v-model="form.media_dir" placeholder="./media" /></label>
          <label class="field"><span>保留天数（到期模拟清理）</span><input class="input num" type="number" min="1" v-model.number="form.keep_days" /></label>
          <p class="hint">单机版数据保存在本机磁盘，路径相对于程序运行目录。</p>
          <button class="btn" style="margin-top: 12px" @click="cleanModal = true">🧹 一键清理冗余片段 / 作品</button>
        </div>

        <!-- 发布 -->
        <div class="card group">
          <div class="group-title">发布</div>
          <label class="field"><span>默认标签（逗号分隔）</span><input class="input" v-model="form.publish_default_tags" placeholder="直播切片,AI剪辑" /></label>
          <label class="field"><span>投稿分区 ID（tid）</span><input class="input" type="number" v-model.number="form.publish_tid" placeholder="244" /></label>
          <div class="hint" style="margin: 10px 0 6px">Bilibili 上传凭证（real 模式必填；浏览器 F12 → Application → Cookies 复制，仅存本机）</div>
          <label class="field"><span>SESSDATA</span><input class="input" v-model="form.bili_sessdata" placeholder="登录后自动掩码显示，重新粘贴可覆盖" /></label>
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
        <span class="hint">修改后需点击保存；自动运行开关与侧栏底部开关联动一致。</span>
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
  </div>
</template>

<style scoped>
.settings-page { display: flex; flex-direction: column; gap: 16px; }
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
.kv { display: flex; gap: 12px; font-size: 13px; margin-bottom: 8px; align-items: center; }
.kv .hint { flex: none; width: 76px; }
.kv .dot { margin-right: 4px; }
.save-bar { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; position: sticky; bottom: 0; backdrop-filter: blur(10px); }
.reset-list { margin: 0 0 10px 18px; font-size: 13px; color: var(--text-2); }
</style>
