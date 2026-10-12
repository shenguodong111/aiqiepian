import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'dashboard', component: () => import('../pages/Dashboard.vue'), meta: { title: '总览' } },
  { path: '/rooms', name: 'rooms', component: () => import('../pages/Rooms.vue'), meta: { title: '直播间管理' } },
  { path: '/recordings', name: 'recordings', component: () => import('../pages/Recordings.vue'), meta: { title: '录制任务' } },
  { path: '/clips', name: 'clips', component: () => import('../pages/Clips.vue'), meta: { title: '片段分析' } },
  { path: '/works', name: 'works', component: () => import('../pages/Works.vue'), meta: { title: '剪辑工作台' } },
  { path: '/review', name: 'review', component: () => import('../pages/Review.vue'), meta: { title: '审核中心' } },
  { path: '/publish', name: 'publish', component: () => import('../pages/Publish.vue'), meta: { title: '发布中心' } },
  { path: '/compilations', name: 'compilations', component: () => import('../pages/Compilations.vue'), meta: { title: '合集' } },
  { path: '/media', name: 'media', component: () => import('../pages/Media.vue'), meta: { title: '素材库' } },
  { path: '/settings', name: 'settings', component: () => import('../pages/Settings.vue'), meta: { title: '设置' } },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
