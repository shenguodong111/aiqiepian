// 通用格式化工具与状态字典

export function fmtDuration(sec) {
  if (sec == null || isNaN(sec)) return '--'
  sec = Math.floor(sec)
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

export function fmtLongDuration(sec) {
  if (sec == null || isNaN(sec)) return '0 分钟'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`
}

export function fmtBytes(b) {
  if (b == null || isNaN(b)) return '--'
  if (b < 1024) return b + ' B'
  const units = ['KB', 'MB', 'GB', 'TB']
  let i = -1
  let v = b
  do { v /= 1024; i++ } while (v >= 1024 && i < units.length - 1)
  return v.toFixed(v >= 100 ? 0 : 1) + ' ' + units[i]
}

export function fmtTime(iso) {
  if (!iso) return '--'
  const d = new Date(iso)
  if (isNaN(d)) return '--'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function fmtClock(iso) {
  if (!iso) return '--'
  const d = new Date(iso)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function relTime(iso) {
  if (!iso) return '暂无'
  const diff = Date.now() - new Date(iso).getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86400_000) return Math.floor(diff / 3600_000) + ' 小时前'
  return Math.floor(diff / 86400_000) + ' 天前'
}

// ---------- 状态字典 ----------

export const PLATFORMS = { bilibili: 'B站', douyu: '斗鱼', huya: '虎牙', douyin: '抖音' }

/** 真实引擎可开播检测 + 自动录制的平台；其余只能录入存档，不会自动开录 */
export const RECORDABLE_PLATFORMS = ['bilibili', 'douyu', 'huya']

export const ROOM_STATUS = {
  OFF: { label: '未监控', cls: 'off' },
  IDLE: { label: '空闲', cls: 'idle' },
  MONITORING: { label: '监控中', cls: 'on' },
  RECORDING: { label: '录制中', cls: 'rec' },
}

export const SESSION_STATUS = {
  MONITORING: '监控中', RECORDING: '录制中', ANALYZING: '分析中', IDLE: '空闲', FINISHED: '已结束',
}

export const ANALYZE_STATUS = { NONE: '未分析', PENDING: '待分析', RUNNING: '分析中', DONE: '已完成' }

export const CLIP_STATUS = { PENDING: '待成片', RENDERED: '已成片', DISCARDED: '已丢弃' }

export const WORK_STATUS = {
  DRAFT: { label: '草稿', cls: 'gray' },
  RENDERING: { label: '渲染中', cls: 'cyan' },
  READY: { label: '待送审', cls: 'blue' },
  APPROVED: { label: '已通过', cls: 'green' },
  REJECTED: { label: '已拒绝', cls: 'red' },
  PUBLISHING: { label: '发布中', cls: 'cyan' },
  PUBLISHED: { label: '已发布', cls: 'green' },
  PUBLISH_FAILED: { label: '发布失败', cls: 'red' },
}

export const JOB_STATUS = {
  PENDING: { label: '排队中', cls: 'gray' },
  RUNNING: { label: '执行中', cls: 'cyan' },
  SUCCESS: { label: '成功', cls: 'green' },
  FAILED: { label: '失败', cls: 'red' },
  SKIPPED: { label: '跳过', cls: 'gray' },
}

export const JOB_TYPES = { MONITOR: '监控', RECORD: '录制', ANALYZE: '分析', RENDER: '成片', REVIEW: '审核', PUBLISH: '发布' }

export const PUBLISH_STATUS = {
  QUEUED: { label: '排队中', cls: 'gray' },
  UPLOADING: { label: '上传中', cls: 'cyan' },
  PLATFORM_REVIEW: { label: '平台审核中', cls: 'blue' },
  PUBLISHED: { label: '已发布', cls: 'green' },
  FAILED: { label: '失败', cls: 'red' },
}

export const REVIEW_DECISION = {
  AUTO_PASS: { label: '自动通过', cls: 'green' },
  AUTO_REJECT: { label: '自动拒绝', cls: 'red' },
  MANUAL: { label: '待人工', cls: 'blue' },
  MANUAL_PASS: { label: '人工放行', cls: 'green' },
  MANUAL_REJECT: { label: '人工拒绝', cls: 'red' },
}

export const SUBTITLE_STYLES = { big: '大字', outline: '描边', none: '无' }
export const ASPECTS = { '9:16': '竖屏 9:16', '16:9': '横屏 16:9', '1:1': '方形 1:1' }
export const ANALYZE_TEMPLATES = { highlight: '精彩片段', full: '全程切片', clip: '高光集锦' }

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    let ok = false
    try { ok = document.execCommand('copy') } catch { ok = false }
    document.body.removeChild(ta)
    return ok
  }
}
