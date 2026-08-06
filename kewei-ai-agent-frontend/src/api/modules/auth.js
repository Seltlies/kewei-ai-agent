import { http } from '../http'

/**
 * 获取当前匿名或登录会话的 CSRF Token，Token 由认证 Store 保存在页面内存中。
 */
export function getCsrf() {
  return http.get('/auth/csrf')
}

/**
 * 查询服务端认证会话对应的当前账号，用于页面刷新后的身份恢复。
 */
export function getCurrentAccount() {
  return http.get('/auth/me')
}

/**
 * 使用账号密码建立服务端认证会话。
 */
export function loginAccount(payload) {
  return http.post('/auth/login', payload)
}

/**
 * 注册普通账号并由后端自动建立认证会话。
 */
export function registerAccount(payload) {
  return http.post('/auth/register', payload)
}

/**
 * 注销当前设备的认证会话，不影响账号在其他设备上的登录状态。
 */
export function logoutAccount() {
  return http.post('/auth/logout')
}
