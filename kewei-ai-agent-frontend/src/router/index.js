import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { pinia } from '../stores/pinia'

const routes = [
  {
    path: '/',
    name: 'landing',
    component: () => import('../views/LandingView.vue'),
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/ChatView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/console',
    name: 'console',
    component: () => import('../views/ConsoleView.vue'),
    meta: { requiresAdmin: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

/**
 * 路由进入前恢复服务端身份，并在业务组件渲染前阻止访客进入 Chat、普通用户进入 Console。
 */
router.beforeEach(async (to) => {
  const authStore = useAuthStore(pinia)
  await authStore.initialize()

  if (to.meta.requiresAdmin && !authStore.isAuthenticated) {
    authStore.showNotice('请先登录后访问')
    authStore.openAuthModal('login', { target: to.fullPath, reason: 'console' })
    return { name: 'landing' }
  }
  if (to.meta.requiresAdmin && !authStore.isAdmin) {
    authStore.showNotice('当前账号无 Console 访问权限')
    return { name: 'landing' }
  }
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    authStore.openAuthModal('login', { target: to.fullPath, reason: 'chat' })
    return { name: 'landing' }
  }
  return true
})

export default router
