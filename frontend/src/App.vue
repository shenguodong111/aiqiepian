<script setup>
import { onMounted } from 'vue'
import MainLayout from './layouts/MainLayout.vue'
import ToastHost from './components/ToastHost.vue'
import { loadSettings } from './stores/app'

onMounted(() => loadSettings())
</script>

<template>
  <MainLayout />
  <ToastHost />
</template>
