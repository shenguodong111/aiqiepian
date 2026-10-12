<script setup>
defineProps({
  total: { type: Number, required: true },
  page: { type: Number, required: true },
  size: { type: Number, default: 20 },
})
const emit = defineEmits(['update:page'])
</script>

<template>
  <div class="pager" v-if="total > 0">
    <span class="num">共 {{ total }} 条 · 第 {{ page }}/{{ Math.max(1, Math.ceil(total / size)) }} 页</span>
    <button class="btn sm ghost" :disabled="page <= 1" @click="emit('update:page', page - 1)">上一页</button>
    <button class="btn sm ghost" :disabled="page >= Math.ceil(total / size)" @click="emit('update:page', page + 1)">下一页</button>
  </div>
</template>
