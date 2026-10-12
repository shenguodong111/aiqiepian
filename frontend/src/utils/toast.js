import { reactive } from 'vue'

export const toasts = reactive([])
let id = 0

function push(type, msg) {
  const t = { id: ++id, type, msg }
  toasts.push(t)
  setTimeout(() => {
    const i = toasts.findIndex(x => x.id === t.id)
    if (i >= 0) toasts.splice(i, 1)
  }, 3200)
}

export const toast = {
  success: (msg) => push('success', msg),
  error: (msg) => push('error', msg),
  info: (msg) => push('info', msg),
}
