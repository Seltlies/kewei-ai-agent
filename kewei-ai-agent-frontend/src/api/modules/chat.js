import { http } from '../http'

/**
 * 查询当前账号的服务端会话列表，keyword 与 cursor 均由后端解释和校验。
 */
export function listChatSessions(params = {}) {
  return http.get('/chat/sessions', { params: { ...params, limit: 20 } })
}

/**
 * 首次发送有效文本时创建正式会话，sessionId 始终由服务端生成。
 */
export function createChatSession(payload) {
  return http.post('/chat/sessions', payload)
}

/**
 * 读取当前账号拥有的指定会话历史消息。
 */
export function listChatMessages(sessionId) {
  return http.get(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`)
}

/**
 * 为纯图片首发创建正式会话并在同一业务请求中保存图片附件。
 */
export function createImageSession(appCode, file) {
  const formData = new FormData()
  formData.append('appCode', appCode)
  formData.append('file', file)
  return http.post('/chat/sessions/attachments', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

/**
 * 将图片附件保存到当前账号已有的正式会话中。
 */
export function uploadSessionAttachment(sessionId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post(`/chat/sessions/${encodeURIComponent(sessionId)}/attachments`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}
