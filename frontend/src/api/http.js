// 统一 fetch 封装：响应结构 {code, data, message}，code!==0 抛错并 toast
import { toast } from '../utils/toast'

const BASE = '/api'

function buildQuery(params) {
  if (!params) return ''
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') usp.set(k, v)
  }
  const s = usp.toString()
  return s ? `?${s}` : ''
}

async function request(method, path, { body, params, silent } = {}) {
  let res
  try {
    res = await fetch(BASE + path + buildQuery(params), {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (e) {
    const msg = '无法连接后端服务，请确认 mock-server 或 Spring Boot 已启动'
    if (!silent) toast.error(msg)
    throw new Error(msg)
  }
  let json
  try {
    json = await res.json()
  } catch {
    const msg = `响应解析失败 (HTTP ${res.status})`
    if (!silent) toast.error(msg)
    throw new Error(msg)
  }
  if (json.code !== 0) {
    const msg = json.message || `请求失败 (HTTP ${res.status})`
    if (!silent) toast.error(msg)
    throw new Error(msg)
  }
  return json.data
}

export const http = {
  get: (path, params, opts) => request('GET', path + (opts?.rawQuery || ''), { params, ...opts }),
  post: (path, body, opts) => request('POST', path, { body, params: opts?.params, ...opts }),
  put: (path, body, opts) => request('PUT', path, { body, params: opts?.params, ...opts }),
  del: (path, opts) => request('DELETE', path, { params: opts?.params, ...opts }),
}
