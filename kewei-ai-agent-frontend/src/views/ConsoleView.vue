<template>
  <div class="page console-page">
    <section class="console-heading">
      <p>ADMIN CONSOLE</p>
      <h1>Console 管理中心</h1>
      <span>仅 admin 可见 · 管理平台账号角色与可用状态</span>
    </section>

    <AccountManagement />

    <div class="grid-2">
      <GlassCard>
        <template #header>
          <h3>系统状态</h3>
        </template>
        <p>后端健康检查：<strong :style="{ color: health.ok ? 'var(--mint)' : 'var(--danger)' }">{{ health.label }}</strong></p>
        <p class="meta">最近检查：{{ health.time || '未检查' }}</p>
        <div class="actions">
          <button class="btn" type="button" @click="checkHealth">立即检查</button>
        </div>
      </GlassCard>

      <GlassCard>
        <template #header>
          <h3>会话管理</h3>
        </template>
        <div class="session-list">
          <div v-for="session in store.sessions" :key="session.sessionId" class="session-item">
            <span>{{ session.title }} · {{ session.sessionId }}</span>
            <div>
              <button class="btn-mini" type="button" @click="store.selectSession(session)">使用</button>
              <button class="btn-mini" type="button" @click="copy(session.sessionId)">复制</button>
            </div>
          </div>
        </div>
      </GlassCard>
    </div>

    <GlassCard>
      <template #header>
        <h3>API 调试面板</h3>
      </template>
      <div class="debug-grid">
        <div>
          <label>接口类型</label>
          <select v-model="debug.mode">
            <option value="sync">love sync</option>
            <option value="sse">love sse</option>
            <option value="sse_emitter">love sse_emitter</option>
            <option value="manus">manus sse</option>
          </select>
        </div>
        <div>
          <label>chatId (manus 可留空)</label>
          <input v-model="debug.chatId" placeholder="chat_xxx" />
        </div>
      </div>
      <div class="debug-box">
        <label>message</label>
        <textarea v-model="debug.message" rows="3" placeholder="输入调试消息" />
      </div>
      <div class="actions">
        <button class="btn" type="button" @click="runDebug">执行调试</button>
        <button class="btn-outline" type="button" @click="clearDebug">清空输出</button>
      </div>
      <div class="code-block">{{ debugOutput || '暂无调试输出' }}</div>
    </GlassCard>

    <GlassCard>
      <template #header>
        <h3>请求日志</h3>
      </template>
      <div class="actions">
        <button class="btn-mini" type="button" @click="store.clearLogs">清空日志</button>
      </div>
      <div class="log-list">
        <div v-for="log in store.logs" :key="log.id" class="log-item">
          <strong>[{{ log.time }}]</strong>
          <span>{{ log.endpoint }}</span>
          <span :class="log.status === 'error' ? 'log-error' : 'log-ok'">{{ log.status }}</span>
          <span>{{ log.elapsed }}ms</span>
          <span>{{ log.message || '-' }}</span>
        </div>
      </div>
    </GlassCard>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import GlassCard from '../components/GlassCard.vue'
import AccountManagement from '../components/AccountManagement.vue'
import { getHealth } from '../api/modules/health'
import { loveChatSync } from '../api/modules/ai'
import { listChatSessions } from '../api/modules/chat'
import { openFetchSSE } from '../api/sse'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'

const store = useAppStore()
const authStore = useAuthStore()
const router = useRouter()
const debugOutput = ref('')
const health = reactive({ ok: false, label: 'unknown', time: '' })
const debug = reactive({
  mode: 'sync',
  chatId: store.currentChatId,
  message: '你好，介绍一下你的平台能力。',
})

onMounted(async () => {
  try {
    await authStore.ensureCsrfToken()
    const response = await listChatSessions()
    store.sessions = response.data?.items || []
  } catch (error) {
    await handleConsoleSecurityError(error)
    authStore.showNotice(error.message || '会话列表加载失败')
  }
})

async function checkHealth() {
  const start = Date.now()
  try {
    const res = await getHealth()
    health.ok = true
    health.label = `healthy (${JSON.stringify(res.data)})`
    health.time = new Date().toLocaleString()
    store.addLog({ endpoint: '/health', status: 'success', elapsed: res.elapsed })
  } catch (error) {
    health.ok = false
    health.label = error.message
    health.time = new Date().toLocaleString()
    store.addLog({ endpoint: '/health', status: 'error', elapsed: Date.now() - start, message: error.message })
  }
}

async function runDebug() {
  debugOutput.value = ''
  const start = Date.now()
  try {
    await authStore.ensureCsrfToken()
    if (debug.mode === 'sync') {
      const res = await loveChatSync({ message: debug.message, chatId: debug.chatId || store.currentChatId })
      debugOutput.value = typeof res.data === 'string' ? res.data : JSON.stringify(res.data, null, 2)
      store.addLog({ endpoint: '/ai/love_app/chat/sync', status: 'success', elapsed: res.elapsed })
      return
    }

    await new Promise((resolve, reject) => {
      let content = ''
      let settled = false
      let timeoutId = null

      const clearIdleTimeout = () => {
        if (timeoutId) {
          clearTimeout(timeoutId)
          timeoutId = null
        }
      }

      const refreshIdleTimeout = () => {
        clearIdleTimeout()
        timeoutId = setTimeout(() => {
          if (settled) return
          settled = true
          conn.close()
          if (content) resolve()
          else reject(new Error('SSE 调试超时'))
        }, 45000)
      }

      // 后端聊天流统一为 POST，请求通过 Fetch 同时携带 Session Cookie 与内存中的 CSRF Token。
      const conn = openFetchSSE({
        path:
          debug.mode === 'sse'
            ? '/ai/love_app/chat/sse'
            : debug.mode === 'sse_emitter'
              ? '/ai/love_app/chat/sse_emitter'
              : '/ai/manus/chat',
        params:
          debug.mode === 'manus'
            ? { message: debug.message, chatId: debug.chatId || store.currentChatId }
            : { message: debug.message, chatId: debug.chatId || store.currentChatId },
        method: 'POST',
        onOpen: () => {
          refreshIdleTimeout()
        },
        onMessage: (chunk) => {
          refreshIdleTimeout()
          content += chunk
          debugOutput.value = content
        },
        onDone: () => {
          if (settled) return
          settled = true
          clearIdleTimeout()
          resolve()
        },
        onError: () => {
          if (settled) return
          settled = true
          clearIdleTimeout()
          reject(new Error('SSE 调试失败'))
        },
      })

      refreshIdleTimeout()
    })

    store.addLog({ endpoint: debug.mode, status: 'success', elapsed: Date.now() - start })
  } catch (error) {
    debugOutput.value = error.message
    store.addLog({ endpoint: debug.mode, status: 'error', elapsed: Date.now() - start, message: error.message })
    await handleConsoleSecurityError(error)
  }
}

/**
 * Console 请求返回未认证或无权限时复核服务端身份，并在角色已失效时立即离开管理页面。
 */
async function handleConsoleSecurityError(error) {
  if (error?.status === 401) {
    authStore.expireAuthentication('/console')
    await router.replace('/')
    return
  }
  if (error?.status === 403) {
    await authStore.revalidateAfterForbidden()
    if (!authStore.isAdmin) {
      authStore.showNotice('当前账号无 Console 访问权限')
      await router.replace('/')
    }
  }
}

function clearDebug() {
  debugOutput.value = ''
}

async function copy(text) {
  if (!text) return
  await navigator.clipboard.writeText(text)
}
</script>

<style scoped>
.console-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.console-heading {
  padding: 8px 8px 4px;
}

.console-heading p {
  color: var(--primary-deep);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.console-heading h1 {
  margin-top: 5px;
  font-size: clamp(30px, 4vw, 42px);
}

.console-heading span {
  display: block;
  margin-top: 8px;
  color: var(--text-subtle);
}

.actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.meta {
  margin-top: 10px;
  color: var(--text-subtle);
  font-size: 13px;
}

.session-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 240px;
  overflow: auto;
}

.session-item {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 8px;
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: space-between;
}

.session-item span {
  font-size: 12px;
  color: var(--text-subtle);
}

.session-item > div {
  display: flex;
  gap: 6px;
}

.debug-grid {
  margin-bottom: 10px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.debug-box {
  margin-bottom: 8px;
}

.log-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 280px;
  overflow: auto;
}

.log-item {
  font-size: 12px;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fff;
  display: grid;
  grid-template-columns: 110px 1.4fr 70px 70px 1fr;
  gap: 10px;
  align-items: center;
}

.log-ok {
  color: var(--mint);
}

.log-error {
  color: var(--danger);
}

@media (max-width: 768px) {
  .debug-grid {
    grid-template-columns: 1fr;
  }

  .log-item {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
