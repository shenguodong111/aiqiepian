<script setup>
import { ref, computed, watch } from 'vue'
import Modal from './Modal.vue'

const props = defineProps({
  type: { type: String, required: true },   // session | work | clip | cover
  id: { type: Number, required: true },
  label: { type: String, default: '预览' },
})
const emit = defineEmits(['close'])

const url = computed(() => `/api/media/stream?type=${props.type}&id=${props.id}`)
const isImage = computed(() => props.type === 'cover')
const err = ref('')

watch(url, () => { err.value = '' })
function onErr() { err.value = '文件不存在或浏览器无法解码该视频（real 模式仅本地文件，mock 演示无真实视频）' }
</script>

<template>
  <Modal :title="label" wide @close="emit('close')">
    <div class="player">
      <img v-if="isImage" :src="url" class="media" alt="封面" @error="onErr" />
      <video v-else class="media" controls autoplay playsinline preload="metadata" @error="onErr" :src="url"></video>
      <p v-if="err" class="err">{{ err }}</p>
      <p v-else class="hint path">{{ url }}</p>
      <div class="modal-foot">
        <button class="btn ghost" @click="emit('close')">关闭</button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.player { display: flex; flex-direction: column; gap: 10px; }
.media { width: 100%; max-height: 62vh; background: #000; border-radius: 12px; }
.path { word-break: break-all; font-size: 11.5px; }
.err { color: #ff8a8a; font-size: 12.5px; }
</style>
