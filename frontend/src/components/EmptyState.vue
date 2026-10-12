<script setup>
defineProps({
  icon: { type: String, default: '◇' },
  text: { type: String, required: true },
  actionLabel: { type: String, default: '' },
})
const emit = defineEmits(['action'])
</script>

<template>
  <div class="empty-state">
    <div class="icon">{{ icon }}</div>
    <p>{{ text }}</p>
    <button v-if="actionLabel" class="btn primary" @click="emit('action')">{{ actionLabel }}</button>
  </div>
</template>
