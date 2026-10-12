<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { store, setAutoRun } from '../stores/app'

const route = useRoute()
const title = computed(() => route.meta.title || 'AI 切片')

const NAV = [
  { to: '/', label: '总览', icon: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z' },
  { to: '/rooms', label: '直播间', icon: 'M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z' },
  { to: '/recordings', label: '录制任务', icon: 'M12 15c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3zm6-9H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm0 14H6V8h12v12zM9 3h6v2H9z' },
  { to: '/clips', label: '片段分析', icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z' },
  { to: '/works', label: '剪辑工作台', icon: 'M9.64 7.64c.24-.7.59-1.34 1.03-1.91L12 2l1.33 3.73c.44.57.79 1.21 1.03 1.91L18 9l-3.61 1.36c-.7.24-1.34.59-1.91 1.03L12 16l-1.33-3.73c-.57-.44-1.21-.79-1.91-1.03L6 9l3.64-1.36zM19 15l-.5 1.5L17 17l1.5.5L19 19l.5-1.5L21 17l-1.5-.5L19 15z' },
  { to: '/review', label: '审核中心', icon: 'M12 2L4 5v6c0 5.25 3.4 10.15 8 11 4.6-.85 8-5.75 8-11V5l-8-3zm-1.2 13.6L7.4 12.2l1.4-1.4 2 2 4.2-4.2 1.4 1.4-5.6 5.6z' },
  { to: '/publish', label: '发布中心', icon: 'M2 21l21-9L2 3v7l15 2-15 2v7z' },
  { to: '/compilations', label: '合集', icon: 'M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z' },
  { to: '/media', label: '素材库', icon: 'M20 6h-8l-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z' },
  { to: '/settings', label: '设置', icon: 'M19.14 12.94c.05-.3.07-.61.07-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.26.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z' },
]

const autoRun = computed(() => !!store.settings?.auto_run)
const connected = computed(() => store.connected)
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-mark"></div>
        <div>
          <div class="logo-name">AI 切片</div>
          <div class="logo-sub">本地控制台</div>
        </div>
      </div>
      <nav class="nav">
        <RouterLink
          v-for="n in NAV" :key="n.to" :to="n.to" class="nav-item"
          :class="{ active: route.path === n.to }"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path :d="n.icon" /></svg>
          <span>{{ n.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <div class="autorun" :class="{ offline: !connected }">
          <div class="autorun-row">
            <span class="dot" :class="!connected ? 'off' : autoRun ? 'on' : 'idle'"></span>
            <span class="autorun-label">{{ !connected ? '后端未连接' : autoRun ? '管线运行中' : '管线已暂停' }}</span>
          </div>
          <div class="autorun-row">
            <span class="autorun-text">自动运行</span>
            <label class="switch" :class="{ disabled: !connected }">
              <input type="checkbox" :checked="autoRun" :disabled="!connected" @change="setAutoRun($event.target.checked)" />
              <span class="track"></span>
            </label>
          </div>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1 class="page-title">{{ title }}</h1>
        <div class="topbar-right">
          <div class="search-ph">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z"/></svg>
            <span>全局搜索（即将开放）</span>
          </div>
          <button class="bell" title="通知（即将开放）">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor"><path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>
          </button>
        </div>
      </header>
      <div class="page-wrap">
        <RouterView v-slot="{ Component }">
          <component :is="Component" :key="route.path" class="page-anim" />
        </RouterView>
      </div>
    </div>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; overflow: hidden; }

.sidebar {
  width: 216px; flex: none; display: flex; flex-direction: column;
  background: #0a0a0e; border-right: 1px solid rgba(255, 255, 255, 0.07);
}
.logo { display: flex; align-items: center; gap: 10px; padding: 20px 18px 16px; }
.logo-mark {
  width: 30px; height: 30px; border-radius: 9px; background: linear-gradient(135deg, #0c8fa1, #00695f);
  box-shadow: 0 2px 10px rgba(12, 143, 161, 0.4); position: relative; flex: none;
}
.logo-mark::after {
  content: ''; position: absolute; left: 11px; top: 8px;
  border-left: 8px solid rgba(255,255,255,.92); border-top: 5px solid transparent; border-bottom: 5px solid transparent;
}
.logo-name { font-size: 15px; font-weight: 700; letter-spacing: 1px; }
.logo-sub { font-size: 10.5px; color: var(--text-3); letter-spacing: 2px; }

.nav { flex: 1; overflow-y: auto; padding: 6px 10px; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 11px; border-radius: 10px;
  color: var(--text-2); font-size: 13.5px; transition: all 0.16s ease; position: relative;
}
.nav-item:hover { color: var(--text); background: rgba(255, 255, 255, 0.04); }
.nav-item.active { color: var(--text); background: var(--accent-soft); }
.nav-item.active::before {
  content: ''; position: absolute; left: -10px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 18px; border-radius: 3px; background: linear-gradient(135deg, #0c8fa1, #00695f);
}

.sidebar-foot { padding: 12px; border-top: 1px solid rgba(255,255,255,.06); }
.autorun { background: var(--surface); border: var(--border); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 9px; transition: opacity .2s; }
.autorun.offline { opacity: 0.6; }
.autorun-row { display: flex; align-items: center; justify-content: space-between; }
.autorun-label { font-size: 12px; color: var(--text-2); display: flex; align-items: center; gap: 7px; }
.autorun-text { font-size: 12.5px; font-weight: 600; }

.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  height: 58px; flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 0 26px; border-bottom: 1px solid rgba(255, 255, 255, 0.06); background: rgba(5, 5, 5, 0.7);
  backdrop-filter: blur(12px);
}
.page-title { font-size: 17px; font-weight: 600; letter-spacing: 0.3px; }
.topbar-right { display: flex; align-items: center; gap: 12px; }
.search-ph {
  display: flex; align-items: center; gap: 7px; color: var(--text-3); font-size: 12px;
  background: var(--surface); border: var(--border); border-radius: 999px; padding: 6px 14px; cursor: default;
}
.bell { background: none; border: none; color: var(--text-3); cursor: pointer; display: flex; padding: 6px; border-radius: 8px; transition: color .15s; }
.bell:hover { color: var(--text); }

.page-wrap { flex: 1; overflow-y: auto; padding: 22px 26px 40px; }
</style>
