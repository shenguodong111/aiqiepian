// 直播自动切片工具 · 模拟后端（与需求文档 §6 API 契约一致）
// 用途：本机无 JDK 时完整演示六环节管线；Spring Boot 后端就绪后可直接切换。
// 运行：node mock-server/server.mjs  → http://localhost:8080（可用环境变量 PORT 覆盖，如 PORT=8081）
import http from 'node:http'

const PORT = +(process.env.PORT || 8080)
const TICK_MS = 3000

// ---------- 内存数据 ----------
let seq = 1
const nid = () => seq++
const now = () => new Date().toISOString()
const rnd = (a, b) => Math.floor(Math.random() * (b - a + 1)) + a
const pick = arr => arr[rnd(0, arr.length - 1)]

const db = {
  rooms: [], sessions: [], clips: [], works: [], reviews: [], publishes: [], jobs: [], compilations: [],
  account: null,
  settings: {
    auto_run: true,
    monitor_interval_sec: 10,
    max_concurrent_record: 3,
    max_concurrent_analyze: 2,
    max_concurrent_render: 2,
    max_concurrent_heavy: 2,
    max_retry: 3,
    auto_pass_score: 85,
    auto_reject_score: 70,
    auto_work_min_score: 70,
    reel_enabled: true,
    clip_dedup_enabled: true,
    sensitive_words: '',
    manual_on_uncertain: true,
    auto_publish: true,
    media_dir: './media',
    keep_days: 30,
    ai_mode: 'mock',
    asr_enabled: true,
    asr_model: 'small',
    asr_language: 'zh',
    asr_python: '',
    publish_default_tags: '直播切片,AI剪辑',
    publish_desc_template: '{房间} {日期} 的精彩瞬间，由 AI 切片自动生成。',
  },
}

const REASONS = [
  '主播情绪高涨，弹幕密度骤增', '抽奖/福利环节，观众互动峰值', '高光操作配合连续礼物特效',
  '争议观点引发弹幕讨论', '名场面复刻，弹幕刷屏', '连麦笑点密集段落',
]
const PLATFORMS = { bilibili: 'B站', douyu: '斗鱼', huya: '虎牙', douyin: '抖音' }

function addJob(type, entityType, entityId, extra = {}) {
  const job = { id: nid(), job_type: type, entity_type: entityType, entity_id: entityId,
    status: 'PENDING', progress: 0, retry_count: 0, log: '', started_at: null, finished_at: null, created_at: now(), ...extra }
  db.jobs.push(job)
  return job
}
const jobOf = (entityType, entityId) => db.jobs.find(j => j.entity_type === entityType && j.entity_id === entityId && j.status !== 'SUCCESS' && j.status !== 'SKIPPED')
const runningCount = t => db.jobs.filter(j => j.job_type === t && j.status === 'RUNNING').length

// ---------- 管线调度（模拟执行） ----------
function startLive(room, manual = false) {
  const s = { id: nid(), room_id: room.id, room_name: room.room_name || room.anchor_name,
    anchor_name: room.anchor_name || room.room_name || '', platform: room.platform,
    status: 'RECORDING', started_at: now(), ended_at: null,
    duration_sec: manual ? rnd(25, 45) : rnd(30, 90), recorded_sec: 0,
    file_path: `${db.settings.media_dir}/recordings/${room.room_name || room.id}_${Date.now()}.mp4`,
    file_size_bytes: 0, analyze_status: 'NONE', created_at: now() }
  db.sessions.push(s)
  room.status = 'RECORDING'
  room.last_live_at = s.started_at
  addJob('RECORD', 'session', s.id)
  return s
}

function finishSession(s) {
  s.status = 'FINISHED'; s.ended_at = now(); s.recorded_sec = s.duration_sec
  s.file_size_bytes = s.duration_sec * rnd(400_000, 900_000)
  s.analyze_status = 'PENDING'
  const room = db.rooms.find(r => r.id === s.room_id)
  if (room) room.status = 'MONITORING'
  addJob('ANALYZE', 'session', s.id)
}

function analyzeDone(s) {
  s.analyze_status = 'DONE'
  const n = rnd(3, 8)
  const room = db.rooms.find(r => r.id === s.room_id)
  const made = []
  for (let i = 0; i < n; i++) {
    const start = rnd(0, Math.max(1, s.duration_sec * 60 - 180))
    const clip = { id: nid(), session_id: s.id, room_id: s.room_id, room_name: s.room_name,
      start_sec: start, end_sec: start + rnd(30, 150), score: rnd(60, 98),
      ai_reason: pick(REASONS), danmaku_heat: rnd(200, 9999), status: 'PENDING',
      file_path: `${db.settings.media_dir}/clips/clip_${seq}.mp4`, created_at: now() }
    clip.duration_sec = clip.end_sec - clip.start_sec
    Object.assign(clip, fakeDanmakuStats(room, s, clip))
    db.clips.push(clip); made.push(clip)
    if (clip.score >= db.settings.auto_work_min_score) makeWork(clip, true)
  }
  return made
}

// M5 弹幕统计假数据（FR-P4-06）：条数 50-3000、密度=条数/分钟、峰值≥密度、去重人数、top5 高频弹幕、主播名
const TOP_WORDS = ['哈哈哈', '666', '前方高能', '泪目', '主播牛逼', 'awsl', '好家伙', '这波可以', '111', '名场面']
function fakeDanmakuStats(room, s, clip) {
  const count = rnd(50, 3000)
  const density = Math.max(1, Math.round(count / Math.max(1, clip.duration_sec) * 60))
  const peak = Math.max(density, Math.round(density * (1.3 + Math.random())))
  const words = [...TOP_WORDS].sort(() => Math.random() - 0.5).slice(0, 5)
  let left = count
  const top = words.map((t, i) => {
    const c = i === words.length - 1 ? Math.max(1, left) : Math.max(1, Math.round(left * (0.5 - i * 0.08)))
    left -= c
    return { t, c }
  })
  return {
    danmaku_count: count,
    danmaku_per_min: density,
    peak_per_min: peak,
    unique_users: rnd(Math.round(count * 0.2), Math.max(3, Math.round(count * 0.8))),
    top_danmaku: JSON.stringify(top),
    anchor_name: room?.anchor_name || room?.room_name || s.room_name || '',
  }
}

function makeWork(clip, auto) {
  const room = db.rooms.find(r => r.id === clip.room_id)
  const st = smartTitle(clip)
  const w = { id: nid(), clip_id: clip.id, room_name: clip.room_name || room?.room_name,
    title: st.kind === 'generic'
      ? `${clip.room_name || '直播'} ${new Date().toLocaleDateString('zh-CN')} 精彩片段 #${clip.id}`
      : st.title,
    description: '', tags: db.settings.publish_default_tags, cover_path: `${db.settings.media_dir}/covers/work_${seq}_cover.jpg`,
    subtitle_enabled: true, subtitle_style: 'big', aspect_ratio: '9:16',
    status: 'DRAFT', score: clip.score, output_path: null, created_at: now(), updated_at: now() }
  db.works.push(w)
  if (!auto) addJob('RENDER', 'work', w.id)
  else addJob('RENDER', 'work', w.id)
  return w
}

// FR-P5-05 智能标题（与后端 TitleGenerator 同规则）：比赛=【战队1】VS【战队2】+时刻；日常=主播名+高频弹幕一句话
function smartTitle(clip) {
  const live = ((clip.room_name || '') + '').trim() || '直播切片'
  const vs = live.match(/([0-9A-Za-z\u4e00-\u9fa5·.\-]{2,12})\s*(?:VS|vs|Vs|对战|对阵)\s*([0-9A-Za-z\u4e00-\u9fa5·.\-]{2,12})/)
  if (vs && /赛|杯|联赛|常规赛|季后赛|小组赛|总决赛|预选|挑战赛|KPL|LPL|PEL|LEC|VCS|IEM|TI|DMC/.test(live)) {
    const clean = t => t.replace(/[【】\[\]()（）]/g, '').trim().slice(0, 10)
    const moment = (clip.score || 0) >= 90 ? '决胜时刻'
      : (clip.ai_reason || '').indexOf('场景切换') >= 0 ? '团战名场面'
      : (clip.score || 0) >= 80 ? '关键一波' : '精彩对决'
    const base = live.replace(vs[0], ' ').replace(/[【】\[\]]/g, '').replace(/\s{2,}/g, ' ').trim().slice(0, 40)
    return { title: (`${base}【${clean(vs[1])}】VS【${clean(vs[2])}】${moment}`).slice(0, 79), kind: 'esports', summary: '比赛切片：从直播标题解析出对阵双方' }
  }
  let top = []
  try { top = JSON.parse(clip.top_danmaku || '[]') } catch (e) { top = [] }
  const words = (Array.isArray(top) ? top : []).filter(x => x && x.c >= 2 && x.t && x.t.length <= 12).slice(0, 3).map(x => x.t)
  if (words.length) {
    const anchorSrc = clip.anchor_name || live
    const anchor = (anchorSrc.replace(/(官方直播间|官方|录播|回放|直播中|房间号|直播间|[:：|｜].*)$/, '').trim().split(' ')[0] || '').slice(0, 8) || '主播'
    const sentence = words.length === 1 ? `满屏「${words[0]}」` : `弹幕齐刷${words.map(w => `「${w}」`).join('')}`
    return { title: (`${anchor}高能瞬间，${sentence}`).slice(0, 79), kind: 'daily', summary: sentence }
  }
  return { title: `${live.replace(/[【】\[\]]/g, '').trim().slice(0, 40)} ${new Date().toLocaleDateString('zh-CN')} 精彩片段`, kind: 'generic', summary: '未采集到弹幕统计，使用通用模板' }
}

function renderDone(w) {
  w.status = 'READY'; w.updated_at = now()
  w.output_path = `${db.settings.media_dir}/works/work_${w.id}.mp4`
  addJob('REVIEW', 'work', w.id)
}

function reviewDone(w) {
  const st = db.settings
  const words = (st.sensitive_words || '').split('\n').map(x => x.trim()).filter(Boolean)
  const text = (w.title || '') + '\n' + (w.description || '')
  const hit = words.find(word => text.includes(word))
  let decision, reason, confidence = Math.min(99, w.score + rnd(-5, 5))
  if (hit) { decision = 'AUTO_REJECT'; reason = `命中敏感词黑名单「${hit}」，自动拒绝`; confidence = 99 }
  else if (w.score >= st.auto_pass_score) { decision = 'AUTO_PASS'; reason = `评分 ${w.score} 达到自动通过线 ${st.auto_pass_score}` }
  else if (w.score < st.auto_reject_score) { decision = 'AUTO_REJECT'; reason = `评分 ${w.score} 低于自动拒绝线 ${st.auto_reject_score}` }
  else if (!st.manual_on_uncertain) { decision = 'AUTO_PASS'; reason = `评分 ${w.score} 处于人工区间，但"不确定项交人工"已关闭，自动通过` }
  else { decision = 'MANUAL'; reason = `评分 ${w.score} 处于人工区间 [${st.auto_reject_score}, ${st.auto_pass_score})，交人工处理` }
  db.reviews.push({ id: nid(), work_id: w.id, decision, confidence, reason, operator: 'system', created_at: now() })
  if (decision === 'AUTO_PASS') {
    w.status = 'APPROVED'; w.updated_at = now()
    if (st.auto_publish && db.account && roomAutoPublishAllowed(w)) queuePublish(w)
  } else if (decision === 'AUTO_REJECT') { w.status = 'REJECTED'; w.updated_at = now() }
}

// 房间级自动发布开关（work → clip → room；查不到房间按默认开 TRUE 处理）
function workRoom(w) {
  const clip = db.clips.find(c => c.id === w.clip_id)
  return clip ? db.rooms.find(r => r.id === clip.room_id) : null
}
const roomAutoPublishAllowed = w => workRoom(w)?.auto_publish !== false

function queuePublish(w) {
  if (db.publishes.find(p => p.work_id === w.id && ['QUEUED','UPLOADING','PLATFORM_REVIEW'].includes(p.status))) return
  db.publishes.push({ id: nid(), work_id: w.id, work_title: w.title, platform: 'bilibili',
    status: 'QUEUED', progress: 0, remote_id: null, error_msg: null, phase_sec: 0, published_at: null, created_at: now(),
    view_count: null, like_count: null, danmaku_count: null, view_synced_at: null })
  w.status = 'PUBLISHING'; w.updated_at = now()
  addJob('PUBLISH', 'work', w.id)
}

function tick() {
  const st = db.settings
  // 1. 监控 → 模拟开播
  if (st.auto_run) {
    for (const room of db.rooms) {
      if (room.monitor_enabled && room.status !== 'RECORDING') {
        if (room.status === 'OFF') { room.status = 'MONITORING'; continue }
        if (Math.random() < 0.18) startLive(room)
      }
    }
  }
  // 2. 录制计时
  for (const s of db.sessions) {
    if (s.status === 'RECORDING') {
      s.recorded_sec += TICK_MS / 1000
      s.file_size_bytes = Math.floor(s.recorded_sec * 600_000)
      const j = jobOf('RECORD', s.id)
      if (j) { j.status = 'RUNNING'; j.started_at ??= now(); j.progress = Math.min(99, Math.floor(s.recorded_sec / s.duration_sec * 100)) }
      if (s.recorded_sec >= s.duration_sec) finishSession(s)
    }
  }
  // 3. job 推进
  const caps = { ANALYZE: st.max_concurrent_analyze, RENDER: st.max_concurrent_render }
  for (const j of db.jobs) {
    if (j.status === 'FAILED' && j.retry_count < st.max_retry && st.auto_run) { j.status = 'PENDING'; j.retry_count++; j.progress = 0 }
    if (j.status === 'PENDING') {
      const cap = caps[j.job_type]
      if (cap !== undefined && runningCount(j.job_type) >= cap) continue
      if (!st.auto_run && ['ANALYZE','RENDER','REVIEW','PUBLISH'].includes(j.job_type)) continue
      j.status = 'RUNNING'; j.started_at = now(); j.log = `${j.job_type} 开始执行（mock）`
      continue
    }
    if (j.status === 'RUNNING') {
      if (Math.random() < 0.04) { j.status = 'FAILED'; j.log += '；模拟异常：引擎超时'; continue }
      j.progress = Math.min(100, j.progress + rnd(12, 35))
      if (j.progress >= 100) {
        j.status = 'SUCCESS'; j.finished_at = now(); j.log += '；执行完成'
        if (j.job_type === 'ANALYZE') { const s = db.sessions.find(x => x.id === j.entity_id); if (s) analyzeDone(s) }
        if (j.job_type === 'RENDER') { const w = db.works.find(x => x.id === j.entity_id); if (w) renderDone(w) }
        if (j.job_type === 'REVIEW') { const w = db.works.find(x => x.id === j.entity_id); if (w) reviewDone(w) }
        if (j.job_type === 'PUBLISH') { /* 发布状态机单独推进 */ }
      }
    }
  }
  // 4. 发布状态机
  for (const p of db.publishes) {
    if (!st.auto_run) continue
    p.phase_sec += TICK_MS / 1000
    if (p.status === 'QUEUED' && p.phase_sec > 3) { p.status = 'UPLOADING'; p.phase_sec = 0 }
    else if (p.status === 'UPLOADING') {
      p.progress = Math.min(99, Math.floor(p.phase_sec / 8 * 100))
      if (p.phase_sec > 8) { p.status = 'PLATFORM_REVIEW'; p.phase_sec = 0; p.progress = 100 }
    } else if (p.status === 'PLATFORM_REVIEW' && p.phase_sec > 5) {
      if (Math.random() < 0.9) {
        p.status = 'PUBLISHED'; p.remote_id = `BV1mock${String(p.id).padStart(6, '0')}`; p.published_at = now()
        // 发布即带一组稳定随机统计值（模拟真实稿件回放量），同步时间=发布时间
        p.view_count = rnd(120, 25000); p.like_count = rnd(5, Math.max(6, Math.floor(p.view_count / 8)))
        p.danmaku_count = rnd(0, Math.max(1, Math.floor(p.view_count / 30))); p.view_synced_at = now()
      }
      else { p.status = 'FAILED'; p.error_msg = '平台审核未通过（模拟）：标题含敏感词' }
      const w = db.works.find(x => x.id === p.work_id)
      if (w) { w.status = p.status === 'PUBLISHED' ? 'PUBLISHED' : 'PUBLISH_FAILED'; w.updated_at = now() }
    }
  }
  // 清理超老 job
  if (db.jobs.length > 500) db.jobs.splice(0, db.jobs.length - 500)
}
function tickSafe() { try { tick() } catch (e) { console.error('[mock] tick error:', e) } }
setInterval(tickSafe, TICK_MS)
process.on('uncaughtException', e => console.error('[mock] uncaught:', e))

// ---------- HTTP ----------
const ok = (res, data) => { res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ code: 0, data, message: 'ok' })) }
const err = (res, msg, code = 400) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ code, data: null, message: msg })) }
const page = (list, q) => { const p = +(q.page || 1), s = +(q.size || 20); return { list: list.slice((p - 1) * s, p * s), total: list.length, page: p, size: s } }
const byId = getArr => id => getArr().find(x => x.id === +id)
const findRoom = byId(() => db.rooms)
const findSession = byId(() => db.sessions)
const findClip = byId(() => db.clips)
const findWork = byId(() => db.works)

// 删除作品（含审核/发布记录）；已发布且非 force 返回 false（跳过）。mock 无真实文件，freed 恒 0。
function dropWork(w, force) {
  if (w.status === 'PUBLISHED' && !force) return false
  db.works = db.works.filter(x => x.id !== w.id)
  db.reviews = db.reviews.filter(r => r.work_id !== w.id)
  db.publishes = db.publishes.filter(p => p.work_id !== w.id)
  return true
}
function dropClip(c) { db.clips = db.clips.filter(x => x.id !== c.id) }

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end() }
  const [path] = req.url.split('?')
  const q = Object.fromEntries(new URL(req.url, 'http://x').searchParams)
  let body = {}
  if (['POST','PUT'].includes(req.method)) { try { body = JSON.parse(await new Promise(r => { let d = ''; req.on('data', c => d += c); req.on('end', () => r(d || '{}')) })) } catch { return err(res, 'invalid json') } }
  const api = path.replace(/^\/api/, '')
  let lastMatch = null
  const m = (re) => (lastMatch = api.match(re))

  // 总览
  if (req.method === 'GET' && api === '/dashboard/summary') {
    const today = new Date().toDateString()
    const isToday = t => t && new Date(t).toDateString() === today
    return ok(res, {
      stats: {
        rooms_monitoring: db.rooms.filter(r => ['MONITORING','RECORDING'].includes(r.status) && r.monitor_enabled).length,
        today_record_sec: db.sessions.filter(s => isToday(s.started_at)).reduce((a, s) => a + s.recorded_sec, 0),
        today_clips: db.clips.filter(c => isToday(c.created_at)).length,
        today_published: db.publishes.filter(p => isToday(p.published_at)).length,
      },
      pipeline: [
        { stage: '监控', running: db.rooms.filter(r => r.status === 'MONITORING').length },
        { stage: '录制', running: db.sessions.filter(s => s.status === 'RECORDING').length },
        { stage: '分析', running: db.jobs.filter(j => j.job_type === 'ANALYZE' && j.status === 'RUNNING').length },
        { stage: '成片', running: db.jobs.filter(j => j.job_type === 'RENDER' && j.status === 'RUNNING').length },
        { stage: '审核', running: db.jobs.filter(j => j.job_type === 'REVIEW' && j.status === 'RUNNING').length },
        { stage: '发布', running: db.publishes.filter(p => ['QUEUED','UPLOADING','PLATFORM_REVIEW'].includes(p.status)).length },
      ],
      running_jobs: db.jobs.filter(j => ['PENDING','RUNNING','FAILED'].includes(j.status)).slice(-10).reverse(),
      recent_works: [...db.works].slice(-6).reverse(),
      system: { disk_free_gb: 256, engine_version: 'mock-1.0.0', auto_run: db.settings.auto_run },
    })
  }
  // 直播间
  if (req.method === 'GET' && api === '/rooms') {
    let list = db.rooms
    if (q.platform) list = list.filter(r => r.platform === q.platform)
    if (q.status) list = list.filter(r => r.status === q.status)
    if (q.kw) list = list.filter(r => (r.room_name + r.anchor_name + r.room_url).includes(q.kw))
    return ok(res, page(list, q))
  }
  if (req.method === 'POST' && api === '/rooms') {
    if (!body.platform || !body.room_url) return err(res, '参数不完整：platform/room_url 必填')
    const room = { id: nid(), platform: body.platform, room_url: body.room_url, room_name: body.room_name || '', anchor_name: body.anchor_name || '',
      monitor_enabled: body.monitor_enabled ?? true, auto_record: body.auto_record ?? true, auto_publish: body.auto_publish ?? true, analyze_template: body.analyze_template || 'highlight',
      publish_tags: body.publish_tags || '',
      status: (body.monitor_enabled ?? true) ? 'MONITORING' : 'OFF', last_live_at: null, created_at: now(), updated_at: now() }
    db.rooms.push(room); return ok(res, room)
  }
  const q_allow = b => b.platform && b.room_url
  if (m(/^\/rooms\/(\d+)$/)) {
    const room = findRoom(lastMatch[1])
    if (!room) return err(res, '直播间不存在', 404)
    if (req.method === 'PUT') { Object.assign(room, body, { updated_at: now() })
      if ('monitor_enabled' in body) { if (room.monitor_enabled) { if (room.status === 'OFF') room.status = 'MONITORING' } else if (room.status === 'MONITORING') room.status = 'OFF' }
      return ok(res, room) }
    if (req.method === 'DELETE') {
      if (room.status === 'RECORDING') return err(res, '录制中禁止删除')
      db.rooms.splice(db.rooms.indexOf(room), 1); return ok(res, true)
    }
  }
  if (m(/^\/rooms\/(\d+)\/toggle$/)) { const room = findRoom(lastMatch[1]); if (!room) return err(res, '不存在', 404)
    room.monitor_enabled = !!body.enabled; room.status = body.enabled ? 'MONITORING' : 'OFF'; return ok(res, room) }
  if (m(/^\/rooms\/(\d+)\/check$/) && req.method === 'POST') { const room = findRoom(lastMatch[1]); if (!room) return err(res, '不存在', 404)
    const live = Math.random() < 0.5
    if (live && room.status !== 'RECORDING') startLive(room, true)
    return ok(res, { live }) }
  // 录制
  if (req.method === 'GET' && api === '/recordings') {
    let list = [...db.sessions].reverse()
    if (q.status === 'active') list = list.filter(s => s.status === 'RECORDING')
    if (q.status === 'history') list = list.filter(s => s.status !== 'RECORDING')
    if (q.roomId) list = list.filter(s => s.room_id === +q.roomId)
    return ok(res, page(list, q))
  }
  if (req.method === 'POST' && api === '/recordings') {
    const room = findRoom(body.roomId)
    if (!room) return err(res, '直播间不存在')
    if (room.status === 'RECORDING') return err(res, '该直播间已有进行中的录制')
    return ok(res, startLive(room, true))
  }
  if (m(/^\/recordings\/(\d+)\/stop$/) && req.method === 'POST') { const s = findSession(lastMatch[1]); if (!s || s.status !== 'RECORDING') return err(res, '会话不在录制中')
    s.duration_sec = Math.floor(s.recorded_sec); finishSession(s); return ok(res, s) }
  if (m(/^\/recordings\/(\d+)\/analyze$/) && req.method === 'POST') { const s = findSession(lastMatch[1]); if (!s) return err(res, '不存在', 404)
    s.analyze_status = 'PENDING'; addJob('ANALYZE', 'session', s.id); return ok(res, true) }
  // 片段
  if (req.method === 'GET' && api === '/clips') {
    let list = [...db.clips].reverse()
    if (q.sessionId) list = list.filter(c => c.session_id === +q.sessionId)
    if (q.minScore) list = list.filter(c => c.score >= +q.minScore)
    if (q.status) list = list.filter(c => c.status === q.status)
    return ok(res, page(list, q))
  }
  if (m(/^\/clips\/(\d+)$/)) { const c = findClip(lastMatch[1]); if (!c) return err(res, '不存在', 404)
    if (req.method === 'PUT') { c.start_sec = body.start_sec ?? c.start_sec; c.end_sec = body.end_sec ?? c.end_sec; c.duration_sec = c.end_sec - c.start_sec; return ok(res, c) }
    if (req.method === 'DELETE') { const force = q.force === 'true'; const r = { removed_clips: 0, removed_works: 0, skipped_published: 0, freed_bytes: 0 }
      let blocked = false
      for (const w of db.works.filter(x => x.clip_id === c.id)) { if (dropWork(w, force)) r.removed_works++; else { r.skipped_published++; blocked = true } }
      if (!blocked) { dropClip(c); r.removed_clips = 1 }
      return ok(res, r) } }
  if (api === '/clips/batch-delete' && req.method === 'POST') {
    const force = !!body.force; const r = { removed_clips: 0, removed_works: 0, skipped_published: 0, freed_bytes: 0 }
    for (const id of (body.ids || [])) { const c = findClip(id); if (!c) continue
      let blocked = false
      for (const w of db.works.filter(x => x.clip_id === c.id)) { if (dropWork(w, force)) r.removed_works++; else { r.skipped_published++; blocked = true } }
      if (!blocked) { dropClip(c); r.removed_clips++ } }
    return ok(res, r) }
  if (m(/^\/clips\/(\d+)\/discard$/) && req.method === 'POST') { const c = findClip(lastMatch[1]); if (!c) return err(res, '不存在', 404); c.status = 'DISCARDED'; return ok(res, c) }
  if (m(/^\/clips\/(\d+)\/restore$/) && req.method === 'POST') { const c = findClip(lastMatch[1]); if (!c) return err(res, '不存在', 404); if (c.status !== 'DISCARDED') return err(res, '仅已丢弃的片段可恢复'); c.status = 'PENDING'; return ok(res, c) }
  if (m(/^\/clips\/(\d+)\/make-work$/) && req.method === 'POST') { const c = findClip(lastMatch[1]); if (!c) return err(res, '不存在', 404)
    c.status = 'RENDERED'; return ok(res, makeWork(c, false)) }
  if (api === '/clips/batch-make-work' && req.method === 'POST') {
    const made = (body.ids || []).map(id => findClip(id)).filter(c => c && c.status === 'PENDING').map(c => { c.status = 'RENDERED'; return makeWork(c, false) })
    return ok(res, made) }
  // 作品
  if (req.method === 'GET' && api === '/works') {
    let list = [...db.works].reverse()
    if (q.status) list = list.filter(w => w.status === q.status)
    return ok(res, page(list, q))
  }
  if (req.method === 'POST' && api === '/works') { const c = findClip(body.clipId); if (!c) return err(res, '片段不存在'); c.status = 'RENDERED'; return ok(res, makeWork(c, false)) }
  if (api === '/works/batch-delete' && req.method === 'POST') {
    const force = !!body.force; const r = { removed_works: 0, skipped_published: 0, freed_bytes: 0 }
    for (const id of (body.ids || [])) { const w = findWork(id); if (!w) continue; if (dropWork(w, force)) r.removed_works++; else r.skipped_published++ }
    return ok(res, r) }
  if (m(/^\/works\/(\d+)(?:\/([\w-]+))?$/)) {
    const w = findWork(lastMatch[1]); if (!w) return err(res, '不存在', 404)
    const act = lastMatch[2]
    if (!act && req.method === 'PUT') { Object.assign(w, body, { updated_at: now() }); return ok(res, w) }
    if (!act && req.method === 'DELETE') { if (!dropWork(w, q.force === 'true')) return err(res, '已发布作品默认不删除（远端稿件仍在 B 站，此处仅删本地记录）；如确需移除请强制删除'); return ok(res, { removed_works: 1, freed_bytes: 0 }) }
    if (act === 'export' && req.method === 'POST') { w.status = 'RENDERING'; addJob('RENDER', 'work', w.id); return ok(res, w) }
    if (act === 'submit-review' && req.method === 'POST') { if (w.status !== 'READY') return err(res, '仅已完成导出的作品可送审'); addJob('REVIEW', 'work', w.id); return ok(res, true) }
    if (act === 'publish' && req.method === 'POST') { if (!db.account) return err(res, '请先绑定发布账号'); if (!['READY','APPROVED'].includes(w.status)) return err(res, '当前状态不可发布'); queuePublish(w); return ok(res, w) }
    if (act === 'suggest-title' && req.method === 'POST') { const c = db.clips.find(x => x.id === w.clip_id); if (!c) return err(res, '作品无关联片段，无法生成标题'); return ok(res, smartTitle(c)) }
  }
  // 审核
  if (api === '/review/pending' && req.method === 'GET') {
    const manualIds = new Set(db.reviews.filter(r => r.decision === 'MANUAL').map(r => r.work_id))
    const list = db.works.filter(w => w.status === 'READY' && manualIds.has(w.id)).reverse()
    return ok(res, page(list, q))
  }
  if (m(/^\/review\/(\d+)\/decision$/) && req.method === 'POST') {
    const w = findWork(lastMatch[1]); if (!w) return err(res, '不存在', 404)
    const pass = body.decision === 'pass'
    if (!pass && !body.reason) return err(res, '拒绝必须填写原因')
    w.status = pass ? 'APPROVED' : 'REJECTED'; w.updated_at = now()
    db.reviews.push({ id: nid(), work_id: w.id, decision: pass ? 'MANUAL_PASS' : 'MANUAL_REJECT', confidence: 100, reason: body.reason || '人工放行', operator: 'local', created_at: now() })
    if (pass && db.settings.auto_publish && db.account && roomAutoPublishAllowed(w)) queuePublish(w)
    return ok(res, w)
  }
  if (api === '/review/records' && req.method === 'GET') return ok(res, page([...db.reviews].reverse(), q))
  if (api === '/review/rules' && req.method === 'GET') return ok(res, pickRules())
  if (api === '/review/rules' && req.method === 'PUT') { Object.assign(db.settings, body); return ok(res, pickRules()) }
  function pickRules() { const s = db.settings; return { auto_pass_score: s.auto_pass_score, auto_reject_score: s.auto_reject_score, auto_work_min_score: s.auto_work_min_score } }
  // 发布
  if (api === '/publish/account' && req.method === 'GET') return ok(res, db.account)
  if (api === '/publish/account/bind' && req.method === 'POST') { db.account = { platform: 'bilibili', nickname: body.nickname || 'AI切片演示号', bound_at: now() }; return ok(res, db.account) }
  if (api === '/publish/account' && req.method === 'DELETE') { db.account = null; return ok(res, true) }
  if (api === '/publish/tasks' && req.method === 'GET') {
    let list = [...db.publishes].reverse()
    if (q.status) list = list.filter(p => p.status === q.status)
    return ok(res, page(list, q))
  }
  if (m(/^\/publish\/tasks\/(\d+)\/retry$/) && req.method === 'POST') { const p = db.publishes.find(x => x.id === +lastMatch[1]); if (!p) return err(res, '不存在', 404)
    p.status = 'QUEUED'; p.phase_sec = 0; p.progress = 0; p.error_msg = null; return ok(res, p) }
  if (api === '/publish/records' && req.method === 'GET') return ok(res, page(db.publishes.filter(p => p.status === 'PUBLISHED').reverse(), q))
  if (api === '/publish/views/sync' && req.method === 'POST') {
    // 模拟播放量回拉：已发布项小幅增长并刷新同步时间（真实后端走 B 站公开 view 接口）
    let synced = 0
    for (const p of db.publishes) {
      if (p.status !== 'PUBLISHED') continue
      p.view_count = (p.view_count || rnd(120, 25000)) + rnd(0, 400)
      p.like_count = (p.like_count || 0) + rnd(0, 40)
      p.danmaku_count = (p.danmaku_count || 0) + rnd(0, 20)
      p.view_synced_at = now()
      synced++
    }
    return ok(res, { synced, failed: 0 })
  }
  // 合集（多作品串联）
  if (api === '/compilations' && req.method === 'GET') return ok(res, page([...db.compilations].reverse(), q))
  if (api === '/compilations' && req.method === 'POST') {
    const title = (body.title || '').trim()
    if (!title) return err(res, '合集标题必填')
    const ids = body.work_ids || body.workIds || []
    if (!Array.isArray(ids) || !ids.length) return err(res, '请至少选择一个作品')
    const works = []
    for (const id of ids) {
      const w = findWork(id)
      if (!w) return err(res, `作品 ${id} 不存在`)
      if (!['READY', 'APPROVED', 'PUBLISHED'].includes(w.status)) return err(res, `作品《${w.title}》状态为 ${w.status}，仅 READY/APPROVED/PUBLISHED 可入合集`)
      if (!w.output_path) return err(res, `作品《${w.title}》缺少成片文件`)
      works.push(w)
    }
    // 时长快照：片段时长优先，缺失时给 30~90s 随机
    const total = works.reduce((a, w) => {
      const clip = db.clips.find(c => c.id === w.clip_id)
      return a + (clip?.duration_sec || rnd(30, 90))
    }, 0)
    const c = { id: nid(), title, work_ids: works.map(w => w.id).join(','), work_count: works.length,
      total_duration_sec: total, status: 'DRAFT', output_path: null, progress: 0, error_msg: null,
      created_at: now(), updated_at: now() }
    db.compilations.push(c)
    return ok(res, c)
  }
  if (m(/^\/compilations\/(\d+)(?:\/(render))?$/)) {
    const c = db.compilations.find(x => x.id === +lastMatch[1])
    if (!c) return err(res, '合集不存在', 404)
    if (lastMatch[2] === 'render' && req.method === 'POST') {
      if (c.status === 'RENDERING') return ok(res, c) // 幂等：渲染中直接返回
      c.status = 'RENDERING'; c.progress = 0; c.error_msg = null; c.updated_at = now()
      // 假进度：500ms 一跳，完成后 READY + 产物路径（真实后端为 ffmpeg concat）
      const iv = setInterval(() => {
        c.progress = Math.min(100, c.progress + rnd(12, 34))
        if (c.progress >= 100) {
          clearInterval(iv)
          c.status = 'READY'; c.output_path = `${db.settings.media_dir}/compilations/compilation_${c.id}.mp4`; c.updated_at = now()
        }
      }, 500)
      return ok(res, c)
    }
    if (!lastMatch[2] && req.method === 'DELETE') {
      if (c.status === 'RENDERING') return err(res, '生成中的合集不可删除')
      db.compilations.splice(db.compilations.indexOf(c), 1)
      return ok(res, true)
    }
  }
  // 素材
  if (api === '/media' && req.method === 'GET') {
    const cat = q.category || 'recordings'
    const map = { recordings: db.sessions.map(s => ({ name: s.file_path?.split('/').pop(), path: s.file_path, size_bytes: s.file_size_bytes, created_at: s.started_at, ref: { type: 'session', id: s.id } })),
      clips: db.clips.filter(c => c.file_path).map(c => ({ name: c.file_path.split('/').pop(), path: c.file_path, size_bytes: c.duration_sec * 300_000, created_at: c.created_at, ref: { type: 'clip', id: c.id } })),
      subtitles: db.works.filter(w => w.subtitle_enabled).map(w => ({ name: `work_${w.id}.srt`, path: `${db.settings.media_dir}/subtitles/work_${w.id}.srt`, size_bytes: rnd(2000, 40000), created_at: w.created_at, ref: { type: 'work', id: w.id } })),
      covers: db.works.map(w => ({ name: w.cover_path?.split('/').pop(), path: w.cover_path, size_bytes: rnd(50000, 300000), created_at: w.created_at, ref: { type: 'work', id: w.id } })),
      works: db.works.filter(w => w.output_path).map(w => ({ name: w.output_path.split('/').pop(), path: w.output_path, size_bytes: rnd(5_000_000, 200_000_000), created_at: w.updated_at, ref: { type: 'work', id: w.id } })) }
    return ok(res, page(map[cat] || [], q))
  }
  if (api === '/media/stats' && req.method === 'GET') {
    const rec = db.sessions.reduce((a, s) => a + (s.file_size_bytes || 0), 0)
    const wrk = db.works.length * 80_000_000
    return ok(res, { recordings_bytes: rec, works_bytes: wrk, subtitles_bytes: db.works.length * 20_000, covers_bytes: db.works.length * 150_000, total_bytes: rec + wrk })
  }
  if (api === '/media/cleanup' && req.method === 'POST') {
    // 清理“已发布成片的原始录制”：会话 → 片段 → 已发布作品，命中的会话记录移除（模拟标记删除，不真删盘）
    const publishedWorkIds = new Set(db.works.filter(w => w.status === 'PUBLISHED').map(w => w.id))
    const clipBySession = new Map()
    for (const c of db.clips) { if (!clipBySession.has(c.session_id)) clipBySession.set(c.session_id, new Set()); clipBySession.get(c.session_id).add(c.id) }
    const doomed = new Set()
    for (const [sid, clipIds] of clipBySession) if ([...clipIds].some(id => publishedWorkIds.has(id))) doomed.add(sid)
    db.sessions = db.sessions.filter(s => !doomed.has(s.id))
    return ok(res, { removed_sessions: doomed.size })
  }
  if (api === '/media/delete' && req.method === 'POST') {
    const cat = body.category
    const ids = new Set((body.ids || []).map(Number))
    const force = !!body.force
    let removed = 0, removedWorks = 0, skipped = 0, freed = 0
    const sizeOf = (w) => w.output_path ? 80_000_000 : 0
    if (cat === 'recordings') {
      // 级联：删会话 + 其片段 + 片段派生作品（已发布默认保留，则会话记录留、仅解除原始文件引用）
      for (const sid of [...ids]) {
        const s = db.sessions.find(x => x.id === sid); if (!s) continue
        const clipIds = db.clips.filter(c => c.session_id === sid).map(c => c.id)
        const delWork = w => clipIds.includes(w.clip_id) && (w.status !== 'PUBLISHED' || force)
        for (const w of db.works) if (delWork(w)) { freed += w.output_path ? 80_000_000 : 0; removedWorks++ }
        db.works = db.works.filter(w => !delWork(w))
        const pubClipIds = new Set(db.works.filter(w => clipIds.includes(w.clip_id)).map(w => w.clip_id))
        db.clips = db.clips.filter(c => !(c.session_id === sid && !pubClipIds.has(c.id)))
        if (s.file_path) { freed += s.file_size_bytes || 0; s.file_path = null; s.file_size_bytes = 0 }
        if (pubClipIds.size === 0) db.sessions = db.sessions.filter(x => x.id !== sid)
        removed++
      }
    } else if (cat === 'subtitles') {
      for (const w of db.works) if (ids.has(w.id) && w.subtitle_enabled) { w.subtitle_enabled = false; freed += 20_000; removed++ }
    } else if (cat === 'covers') {
      for (const w of db.works) if (ids.has(w.id) && w.cover_path) { w.cover_path = null; freed += 150_000; removed++ }
    } else if (cat === 'works') {
      for (const w of db.works) if (ids.has(w.id)) {
        if (w.status === 'PUBLISHED' && !force) { skipped++; continue }
        freed += sizeOf(w); removed++
      }
      db.works = db.works.filter(w => !(ids.has(w.id) && !(w.status === 'PUBLISHED' && !force)))
    } else if (cat === 'clips') {
      const clipIds = new Set()
      for (const c of db.clips) if (ids.has(c.id)) clipIds.add(c.id)
      for (const w of db.works) if (clipIds.has(w.clip_id)) {
        if (w.status === 'PUBLISHED' && !force) { skipped++; clipIds.delete(w.clip_id); continue }
        freed += sizeOf(w); removedWorks++
      }
      db.works = db.works.filter(w => !(clipIds.has(w.clip_id) && !(w.status === 'PUBLISHED' && !force)))
      db.clips = db.clips.filter(c => !clipIds.has(c.id))
      removed = clipIds.size
    }
    return ok(res, { removed, removed_works: removedWorks, skipped_published: skipped, freed_bytes: freed })
  }
  // 设置
  if (api === '/settings' && req.method === 'GET') return ok(res, db.settings)
  if (api === '/settings' && req.method === 'PUT') { Object.assign(db.settings, body); return ok(res, db.settings) }
  if (api === '/settings/reset-demo' && req.method === 'POST') {
    db.rooms = []; db.sessions = []; db.clips = []; db.works = []; db.reviews = []; db.publishes = []; db.jobs = []; db.compilations = []; db.account = null
    return ok(res, true)
  }
  // 跨机迁移：配置导出 / 导入（与 Spring 端 /api/config/* 同契约；B 站 Cookie 等凭据一律剔除）
  const CFG_OMIT = ['bili_sessdata', 'bili_jct', 'bili_uid', 'bili_browser_cookie', 'engine_mode']
  const cfgRoom = r => ({
    platform: r.platform, room_url: r.room_url, room_name: r.room_name || '', anchor_name: r.anchor_name || '',
    live_title: r.live_title || '', monitor_enabled: r.monitor_enabled ?? true, auto_record: r.auto_record ?? true,
    auto_publish: r.auto_publish ?? true, analyze_template: r.analyze_template || 'highlight',
    publish_tags: r.publish_tags || '',
  })
  const urlOf = roomId => db.rooms.find(r => r.id === roomId)?.room_url || ''
  const toCfgRow = (row, extra) => {
    const copy = { ...row, src_id: row.id }
    delete copy.id; delete copy.room_id
    return { ...copy, ...extra }
  }
  if (api === '/config/export' && req.method === 'GET') {
    const payload = {
      kind: 'aiqiepian-config', version: 1, exported_at: now(),
      rooms: db.rooms.map(cfgRoom),
      settings: Object.fromEntries(Object.entries(db.settings).filter(([k]) => !CFG_OMIT.includes(k))),
    }
    if (q.include_history === 'true') {
      payload.history = {
        recordings: db.sessions.map(s => toCfgRow(s, { room_url: urlOf(s.room_id) })),
        clips: db.clips.map(c => toCfgRow(c, { room_url: urlOf(db.sessions.find(s => s.id === c.session_id)?.room_id), src_session_id: c.session_id })),
        works: db.works.map(w => {
          const c = db.clips.find(x => x.id === w.clip_id)
          const s = c && db.sessions.find(x => x.id === c.session_id)
          return toCfgRow(w, { room_url: urlOf(s?.room_id), src_clip_id: w.clip_id })
        }),
      }
    }
    return ok(res, payload)
  }
  if (api === '/config/import' && req.method === 'POST') {
    if (body?.kind !== 'aiqiepian-config') return err(res, '文件格式不对：这不是本工具导出的配置 JSON')
    if ((body.version || 1) > 1) return err(res, `配置版本过新（文件 v${body.version}，本机 v1），请先升级程序再导入`)
    const r = { rooms_created: 0, rooms_updated: 0, settings_updated: 0 }
    for (const item of body.rooms || []) {
      if (!item.room_url) continue
      const cfg = cfgRoom(item)
      const exist = db.rooms.find(x => x.room_url === item.room_url)
      if (exist) { Object.assign(exist, cfg, { updated_at: now() }); r.rooms_updated++ }
      else {
        db.rooms.push({ id: nid(), ...cfg, status: cfg.monitor_enabled ? 'MONITORING' : 'OFF', last_live_at: null, created_at: now(), updated_at: now() })
        r.rooms_created++
      }
    }
    const patch = Object.fromEntries(Object.entries(body.settings || {}).filter(([k]) => !CFG_OMIT.includes(k)))
    Object.assign(db.settings, patch)
    r.settings_updated = Object.keys(patch).length
    const h = body.history
    if (h) {
      const sidMap = {}, cidMap = {}
      r.recordings_created = 0; r.recordings_skipped = 0
      for (const s of h.recordings || []) {
        const room = db.rooms.find(x => x.room_url === s.room_url)
        if (!room) { r.recordings_skipped++; continue }
        const dupe = db.sessions.find(x => x.room_id === room.id && x.started_at === s.started_at)
        if (dupe) { sidMap[s.src_id] = dupe.id; r.recordings_skipped++; continue }
        const { src_id, room_url, ...rest } = s
        const row = { ...rest, id: nid(), room_id: room.id, room_name: rest.room_name || room.room_name, platform: rest.platform || room.platform }
        db.sessions.push(row); sidMap[src_id] = row.id; r.recordings_created++
      }
      r.clips_created = 0; r.clips_skipped = 0
      for (const c of h.clips || []) {
        const sessionId = sidMap[c.src_session_id] ?? db.sessions.find(s => s.room_id === db.rooms.find(x => x.room_url === c.room_url)?.id)?.id
        if (!sessionId) { r.clips_skipped++; continue }
        const dupe = db.clips.find(x => x.session_id === sessionId && x.start_sec === c.start_sec && x.end_sec === c.end_sec)
        if (dupe) { cidMap[c.src_id] = dupe.id; r.clips_skipped++; continue }
        const { src_id, src_session_id, room_url, ...rest } = c
        const row = { ...rest, id: nid(), session_id: sessionId, room_id: db.sessions.find(s => s.id === sessionId).room_id }
        db.clips.push(row); cidMap[src_id] = row.id; r.clips_created++
      }
      r.works_created = 0; r.works_skipped = 0
      for (const w of h.works || []) {
        const clipId = cidMap[w.src_clip_id] ?? w.clip_id
        const clip = db.clips.find(x => x.id === clipId)
        if (!clip) { r.works_skipped++; continue }
        const dupe = db.works.find(x => x.clip_id === clipId && x.title === w.title)
        if (dupe) { r.works_skipped++; continue }
        const { src_id, src_clip_id, room_url, ...rest } = w
        db.works.push({ ...rest, id: nid(), clip_id: clipId, room_name: rest.room_name || clip.room_name })
        r.works_created++
      }
    }
    return ok(res, r)
  }
  // 队列
  if (api === '/jobs' && req.method === 'GET') {
    let list = [...db.jobs].reverse()
    if (q.status) list = list.filter(j => j.status === q.status)
    if (q.type) list = list.filter(j => j.job_type === q.type)
    return ok(res, page(list, q))
  }
  if (m(/^\/jobs\/(\d+)\/retry$/) && req.method === 'POST') { const j = db.jobs.find(x => x.id === +lastMatch[1]); if (!j) return err(res, '不存在', 404)
    j.status = 'PENDING'; j.progress = 0; return ok(res, j) }

  err(res, `no route: ${req.method} ${api}`, 404)
})
server.listen(PORT, () => console.log(`[mock] AI切片模拟后端已启动: http://localhost:${PORT}/api  (tick=${TICK_MS}ms)`))
