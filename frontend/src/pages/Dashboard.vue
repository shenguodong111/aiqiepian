<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { toast } from '../utils/toast'
import { store } from '../stores/app'
import LoadingBlock from '../components/LoadingBlock.vue'
import { JOB_STATUS, JOB_TYPES, WORK_STATUS, fmtLongDuration, relTime } from '../utils/format'

const router = useRouter()
const data = ref(null)
const loading = ref(true)
const retrying = ref(null)
let timer = null

async function fetchSummary(silent = false) {
  try {
    data.value = await http.get('/dashboard/summary', null, { silent })
    store.connected = true
  } catch {
    store.connected = false
    if (!silent) toast.error('加载总览数据失败')
  } finally {
    loading.value = false
  }
}

async function retryJob(job) {
  retrying.value = job.id
  try {
    await http.post(`/jobs/${job.id}/retry`)
    toast.success('任务已重新入队')
    fetchSummary(true)
  } catch { /* toast 已由 http 弹出 */ } finally { retrying.value = null }
}

const STAGE_LINKS = { '监控': '/rooms', '录制': '/recordings', '分析': '/clips', '成片': '/works', '审核': '/review', '发布': '/publish' }
const pipeline = computed(() => data.value?.pipeline || [])

const stats = computed(() => {
  const s = data.value?.stats
  if (!s) return []
  return [
    { label: '监控中直播间', value: s.rooms_monitoring, unit: '个' },
    { label: '今日录制时长', value: fmtLongDuration(s.today_record_sec), unit: '' },
    { label: '今日新增片段', value: s.today_clips, unit: '条' },
    { label: '今日已发布', value: s.today_published, unit: '个' },
  ]
})

onMounted(() => {
  fetchSummary()
  timer = setInterval(() => fetchSummary(true), 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="dash">
    <LoadingBlock v-if="loading" />

    <template v-else-if="data">
      <!-- FR-P1-01 管线拓扑 -->
      <div class="card pipe-card">
        <div class="section-head">
          <span class="card-title">生产管线</span>
          <span class="hint">监控 → 录制 → 分析 → 成片 → 审核 → 发布 · 点击节点进入对应页面</span>
        </div>
        <div class="pipe">
          <template v-for="(node, i) in pipeline" :key="node.stage">
            <div class="pipe-node" @click="router.push(STAGE_LINKS[node.stage] || '/')">
              <div class="pipe-idx num">0{{ i + 1 }}</div>
              <div class="pipe-name">{{ node.stage }}</div>
              <div class="pipe-num num" :class="{ live: node.running > 0 }">{{ node.running }}</div>
              <div class="pipe-unit">进行中</div>
            </div>
            <div v-if="i < pipeline.length - 1" class="pipe-arrow">
              <svg width="22" height="12" viewBox="0 0 22 12"><path d="M0 6h18m0 0l-5-5m5 5l-5 5" stroke="rgba(255,255,255,.25)" stroke-width="1.5" fill="none"/></svg>
            </div>
          </template>
        </div>
      </div>

      <!-- FR-P1-02 统计卡 -->
      <div class="grid cols-4 stat-grid">
        <div v-for="s in stats" :key="s.label" class="card stat-card">
          <div class="stat-label">{{ s.label }}</div>
          <div class="stat-value num">{{ s.value }}<span class="stat-unit" v-if="s.unit">{{ s.unit }}</span></div>
          <div class="stat-delta hint">较昨日 —</div>
        </div>
      </div>

      <div class="two-col">
        <!-- FR-P1-03 实时任务 -->
        <div class="card jobs-card">
          <div class="section-head">
            <span class="card-title">实时任务</span>
            <span class="hint">3s 自动刷新</span>
          </div>
          <div v-if="!data.running_jobs?.length" class="hint" style="padding: 24px 0; text-align: center">暂无进行中的任务，管线空闲</div>
          <table v-else class="table">
            <thead>
              <tr><th>环节</th><th>对象</th><th>状态</th><th style="width:120px">进度</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="j in data.running_jobs" :key="j.id">
                <td><span class="badge cyan">{{ JOB_TYPES[j.job_type] || j.job_type }}</span></td>
                <td class="muted num">{{ j.entity_type }}#{{ j.entity_id }}</td>
                <td><span class="badge" :class="JOB_STATUS[j.status]?.cls">{{ JOB_STATUS[j.status]?.label || j.status }}</span></td>
                <td>
                  <div class="progress" :class="{ stripe: j.status === 'RUNNING' }"><i :style="{ width: j.progress + '%' }"></i></div>
                </td>
                <td>
                  <button v-if="j.status === 'FAILED'" class="btn sm" :disabled="retrying === j.id" @click="retryJob(j)">
                    {{ retrying === j.id ? '重试中' : '重试' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="right-col">
          <!-- FR-P1-04 最近成片 -->
          <div class="card">
            <div class="section-head">
              <span class="card-title">最近成片</span>
              <button class="link-btn" @click="router.push('/works')">全部作品 →</button>
            </div>
            <div v-if="!data.recent_works?.length" class="hint" style="padding: 18px 0; text-align: center">尚无成片产出，管线跑完后自动出现在这里</div>
            <div v-else class="works-row">
              <div v-for="w in data.recent_works" :key="w.id" class="work-mini card hover" @click="router.push('/works')">
                <div class="cover-ph">▶<span class="score-tag num">{{ w.score }}</span></div>
                <div class="work-mini-title trunc">{{ w.title }}</div>
                <span class="badge" :class="WORK_STATUS[w.status]?.cls">{{ WORK_STATUS[w.status]?.label || w.status }}</span>
              </div>
            </div>
          </div>

          <!-- FR-P1-05 系统状态 -->
          <div class="card sys-card">
            <div class="section-head"><span class="card-title">系统状态</span></div>
            <div class="sys-grid">
              <div class="sys-item">
                <span class="hint">磁盘可用（media 目录）</span>
                <div class="sys-val num">{{ data.system.disk_free_gb }} GB</div>
              </div>
              <div class="sys-item">
                <span class="hint">引擎版本</span>
                <div class="sys-val">{{ data.system.engine_version }}</div>
              </div>
              <div class="sys-item">
                <span class="hint">自动运行</span>
                <div class="sys-val">
                  <span class="dot" :class="data.system.auto_run ? 'on' : 'idle'"></span>
                  {{ data.system.auto_run ? '已开启' : '已关闭' }}
                </div>
              </div>
              <div class="sys-item">
                <span class="hint">后端连接</span>
                <div class="sys-val">
                  <span class="dot" :class="store.connected ? 'on' : 'off'"></span>
                  {{ store.connected ? '正常' : '断开' }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="card" style="padding: 40px; text-align: center">
      <p class="muted" style="margin-bottom: 14px">无法从后端获取总览数据</p>
      <button class="btn primary" @click="fetchSummary()">重新加载</button>
    </div>
  </div>
</template>

<style scoped>
.dash { display: flex; flex-direction: column; gap: 16px; }

.pipe-card { padding: 18px 22px; }
.pipe { display: flex; align-items: stretch; gap: 4px; overflow-x: auto; }
.pipe-node {
  flex: 1; min-width: 96px; text-align: center; padding: 12px 8px; border-radius: 12px;
  background: var(--surface-2); border: var(--border); cursor: pointer;
  transition: all 0.18s ease;
}
.pipe-node:hover { border-color: rgba(12, 143, 161, 0.5); transform: translateY(-2px); background: var(--surface-3); }
.pipe-idx { font-size: 10px; color: var(--text-3); letter-spacing: 1px; }
.pipe-name { font-size: 13px; font-weight: 600; margin: 2px 0 6px; }
.pipe-num { font-size: 22px; font-weight: 700; color: var(--text-2); transition: color 0.3s; }
.pipe-num.live { color: #35b8cc; text-shadow: 0 0 14px rgba(12, 143, 161, 0.55); }
.pipe-unit { font-size: 10.5px; color: var(--text-3); }
.pipe-arrow { display: flex; align-items: center; flex: none; padding: 0 2px; }

.stat-grid { margin-top: 0; }
.stat-card { padding: 16px 18px; }
.stat-label { font-size: 12px; color: var(--text-2); margin-bottom: 8px; }
.stat-value { font-size: 24px; font-weight: 700; letter-spacing: 0.3px; }
.stat-unit { font-size: 12px; color: var(--text-3); margin-left: 4px; font-weight: 400; }
.stat-delta { margin-top: 6px; font-size: 11px; }

.two-col { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.jobs-card { padding: 18px 22px; }
.right-col { display: flex; flex-direction: column; gap: 16px; }
.right-col .card { padding: 18px 22px; }

.works-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.work-mini { padding: 8px; cursor: pointer; border-radius: 12px; }
.work-mini-title { font-size: 12px; margin: 7px 0 5px; max-width: 100%; }

.sys-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.sys-val { font-size: 15px; font-weight: 600; margin-top: 4px; display: flex; align-items: center; gap: 7px; }

@media (max-width: 1080px) {
  .two-col { grid-template-columns: 1fr; }
}
</style>
