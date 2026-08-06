import { createPinia } from 'pinia'

// 路由守卫和 Vue 应用复用同一个 Pinia 实例，确保首次路由鉴权读取到统一认证状态。
export const pinia = createPinia()
