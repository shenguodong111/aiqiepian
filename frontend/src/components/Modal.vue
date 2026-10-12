<script setup>
defineProps({
  title: { type: String, default: '' },
  wide: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <div class="modal-mask" @click.self="emit('close')">
      <Transition name="pop">
        <div class="modal" :style="wide ? { maxWidth: '720px' } : null">
          <h3>{{ title }}</h3>
          <slot />
        </div>
      </Transition>
    </div>
  </Teleport>
</template>
