// 极简全局 store：settings + auto_run 开关 + 通知（不用 pinia）
import { reactive, watch } from 'vue'
import { http } from '../api/http'
import { toast } from '../utils/toast'

export const store = reactive({
  settings: null,
  settingsLoaded: false,
  connected: false,
  notifCount: 0,
})

export async function loadSettings(silent = true) {
  try {
    store.settings = await http.get('/settings', null, { silent })
    store.settingsLoaded = true
    store.connected = true
  } catch {
    store.connected = false
  }
}

export async function patchSettings(patch) {
  const prev = store.settings
  store.settings = { ...prev, ...patch }
  try {
    const saved = await http.put('/settings', patch)
    store.settings = saved
    return saved
  } catch (e) {
    store.settings = prev
    throw e
  }
}

export function setAutoRun(enabled) {
  return patchSettings({ auto_run: enabled })
    .then(() => toast.success(enabled ? '自动运行已开启，管线恢复调度' : '自动运行已关闭，不再产生新任务'))
    .catch(() => {})
}

watch(() => store.settings, () => {}, { deep: true })
