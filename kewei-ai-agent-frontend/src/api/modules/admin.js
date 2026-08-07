import { http } from '../http'

/**
 * 查询 Console 账号列表。前端固定每页 20 条，关键词、角色、状态和页码全部由后端校验。
 */
export function listAdminAccounts(params = {}) {
  return http.get('/admin/accounts', { params: { ...params, size: 20 } })
}

/**
 * 调整目标账号角色，version 是列表返回的并发令牌，禁止旧页面覆盖其他管理员的新操作。
 */
export function updateAdminAccountRole(accountId, role, version) {
  return http.patch(`/admin/accounts/${encodeURIComponent(accountId)}/role`, { role, version })
}

/**
 * 启用或停用目标账号。后端负责当前账号限制和最后一个正常 admin 的事务校验。
 */
export function updateAdminAccountStatus(accountId, status, version) {
  return http.patch(`/admin/accounts/${encodeURIComponent(accountId)}/status`, { status, version })
}
