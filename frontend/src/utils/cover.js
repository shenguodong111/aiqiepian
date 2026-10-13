import { ref } from 'vue'

// 作品封面取图：后端没有封面列表接口，统一按 work id 走 /api/media/stream。
// v 带上作品更新时间，改了标题或换了底图后（cover_path 不变但时间变了）能立刻穿透缓存刷出新图。
export function coverUrl(w, tick = 0) {
  return `/api/media/stream?type=cover&id=${w.id}&v=${encodeURIComponent(`${w.updated_at || ''}|${tick}`)}`
}

/**
 * 封面文件可能还没生成（建作品时就预置了 cover_path），取图失败要退回 ▶ 占位符。
 * tick 是可选的 ref：本地图重新合成后自增它，既穿透浏览器缓存也让失败的图重试一次。
 */
export function useCoverImage(tick) {
  const broken = ref({}) // 失败过的封面 URL -> true；URL 变了即视为新图再试
  const url = (w) => coverUrl(w, tick?.value ?? 0)
  const shown = (w) => !!w.cover_path && !broken.value[url(w)]
  const onError = (w) => { broken.value = { ...broken.value, [url(w)]: true } }
  return { url, shown, onError }
}
