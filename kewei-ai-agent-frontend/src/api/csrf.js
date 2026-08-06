let csrfHeaderName = 'X-CSRF-TOKEN'
let csrfToken = ''

/**
 * 将后端签发的 CSRF Token 仅保存在当前页面内存中，避免认证安全信息进入浏览器持久化存储。
 * @param {{ headerName?: string, token?: string } | null} payload CSRF 接口响应
 */
export function setCsrfToken(payload) {
  csrfHeaderName = payload?.headerName || 'X-CSRF-TOKEN'
  csrfToken = payload?.token || ''
}

/**
 * 清理当前认证会话对应的 CSRF Token，账号退出或 Session 轮换后必须调用。
 */
export function clearCsrfToken() {
  csrfHeaderName = 'X-CSRF-TOKEN'
  csrfToken = ''
}

/**
 * 为 Axios 与 Fetch 的修改类请求生成统一安全请求头；没有可用 Token 时返回空对象。
 */
export function getCsrfHeaders() {
  return csrfToken ? { [csrfHeaderName]: csrfToken } : {}
}

/**
 * 判断当前页面是否已经取得可用于修改类请求的 CSRF Token。
 */
export function hasCsrfToken() {
  return Boolean(csrfToken)
}
