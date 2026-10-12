<script setup>
import { toasts } from '../utils/toast'

const ICONS = { success: '✓', error: '✕', info: 'ℹ' }
</script>

<template>
  <Teleport to="body">
    <div class="toast-host">
      <TransitionGroup name="pop">
        <div v-for="t in toasts" :key="t.id" class="toast-item" :class="t.type">
          <span :style="{ color: t.type === 'success' ? 'var(--green)' : t.type === 'error' ? 'var(--red)' : '#35b8cc' }">{{ ICONS[t.type] }}</span>
          <span>{{ t.msg }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.pop-enter-active { transition: opacity 0.22s ease, transform 0.22s cubic-bezier(0.34, 1.4, 0.64, 1); }
.pop-leave-active { transition: opacity 0.16s ease, transform 0.16s ease; position: absolute; }
.pop-enter-from { opacity: 0; transform: translateX(24px); }
.pop-leave-to { opacity: 0; transform: translateX(24px); }
</style>
