import { http } from '../http'

export function loveChatSync(params) {
  return http.post('/ai/love_app/chat/sync', null, {
    params,
    timeout: 180000,
  })
}

export function continueManusChat(payload) {
  return http.post('/ai/manus/chat/continue', payload, {
    timeout: 180000,
  })
}
