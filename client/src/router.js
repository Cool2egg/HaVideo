import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from './api'

const routes = [
  { path: '/login', component: () => import('./views/Login.vue') },
  { path: '/', component: () => import('./views/Library.vue') },
  { path: '/videos/:id', component: () => import('./views/VideoDetail.vue') },
  { path: '/qa', component: () => import('./views/Knowledge.vue') },
  { path: '/manage', component: () => import('./views/Admin.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  const token = getToken()
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/'
  return true
})

export default router
