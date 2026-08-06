<template>
  <div class="page chat-page">
    <aside class="session-sidebar">
      <div class="session-heading">
        <h2>我的会话</h2>
        <button class="btn session-create" type="button" :disabled="sending" @click="newChat">＋ 新建</button>
      </div>

      <div class="session-search">
        <span aria-hidden="true" />
        <input
          v-model="store.sessionKeyword"
          type="search"
          placeholder="搜索我的会话"
          aria-label="搜索我的会话"
          @input="scheduleSessionSearch"
          @keydown.enter.prevent="loadSessions({ reset: true })"
        />
      </div>

      <div class="session-list" aria-live="polite">
        <p v-if="store.sessionsLoading && !store.sessions.length" class="session-empty">正在加载会话…</p>
        <p v-else-if="!store.sessions.length" class="session-empty">
          {{ store.sessionKeyword.trim() ? '没有匹配的会话' : '还没有会话，点击新建开始对话' }}
        </p>
        <button
          v-for="session in store.sessions"
          :key="session.sessionId"
          class="session-item"
          :class="{ active: store.currentChatId === session.sessionId }"
          type="button"
          :disabled="sending"
          @click="switchSession(session)"
        >
          <span class="session-status" aria-hidden="true">{{ store.currentChatId === session.sessionId ? '✓' : '' }}</span>
          <span class="session-copy">
            <strong>{{ session.title }}</strong>
            <small>{{ formatSessionTime(session.updateTime) }} · {{ formatAppName(session.appCode) }}</small>
          </span>
        </button>
        <button
          v-if="store.hasMoreSessions"
          class="btn-outline load-more"
          type="button"
          :disabled="store.sessionsLoading"
          @click="loadSessions({ append: true })"
        >
          {{ store.sessionsLoading ? '加载中…' : '加载更多' }}
        </button>
      </div>

      <div class="session-owner-note">
        <span aria-hidden="true">✓</span>
        会话随账号保存，仅你可见
      </div>
    </aside>

    <section class="chat-main">
      <div class="chat-topbar">
        <div>
          <h2>会话中心</h2>
          <p class="account-context"><span /> 当前账号：{{ authStore.currentAccount?.account }} · 仅你可见</p>
        </div>
        <div class="top-actions">
          <select v-model="store.activeApp" :disabled="Boolean(store.currentSession) || sending" aria-label="会话应用">
            <option value="love">Love App</option>
            <option value="manus">Manus</option>
          </select>
          <select v-model="store.chatMode" :disabled="sending" aria-label="回复模式">
            <option v-for="mode in modeOptions" :key="mode.value" :value="mode.value">{{ mode.label }}</option>
          </select>
        </div>
      </div>

      <div ref="messageBox" class="message-list">
        <p v-if="messagesLoading" class="empty-tip">正在恢复会话历史…</p>
        <template v-else>
          <ChatMessage
            v-for="msg in messages"
            :key="msg.id"
            :message="msg"
            @copy="copyText(msg.content)"
            @retry="retryMessage"
          />
        </template>
        <div v-if="!messagesLoading && messages.length === 0" class="empty-tip">
          <strong>{{ store.currentSession ? store.currentSession.title : '开始一段新对话' }}</strong>
          <span>输入问题后，本次会话会安全保存到你的账号中。</span>
        </div>
      </div>

      <div class="chat-input-wrap">
        <div v-if="pinnedTodo" class="todo-pin">
          <div class="todo-pin__header">
            <div>
              <strong>当前任务</strong>
              <p>{{ pinnedTodoCompleted }}/{{ pinnedTodo.items.length }} 已完成</p>
            </div>
            <span class="todo-pin__hint">任务执行中，完成后会归档到聊天历史</span>
          </div>
          <div class="todo-pin__list">
            <div v-for="item in pinnedTodo.items" :key="item.id" class="todo-pin__item" :class="`is-${item.status}`">
              <span class="todo-pin__icon">{{ item.status === 'completed' ? '✓' : item.status === 'in_progress' ? '…' : '○' }}</span>
              <span class="todo-pin__content">{{ item.content }}</span>
              <span class="todo-pin__status">{{ formatTodoStatus(item.status) }}</span>
            </div>
          </div>
        </div>

        <div v-if="pendingQuestions.length" class="question-panel">
          <div class="question-panel__header">
            <strong>需要补充信息</strong>
            <span>回答后会继续执行 Manus</span>
          </div>
          <div v-for="question in pendingQuestions" :key="question.id" class="question-item">
            <label :for="question.id">{{ question.header || '问题' }}</label>
            <p class="question-item__text">{{ question.question }}</p>

            <select
              v-if="question.options?.length && !question.multiSelect"
              :id="question.id"
              v-model="pendingAnswers[question.id]"
            >
              <option value="">请选择</option>
              <option v-for="option in question.options" :key="option.label" :value="option.label">
                {{ option.label }}
              </option>
            </select>

            <div v-else-if="question.options?.length && question.multiSelect" class="question-item__options">
              <label v-for="option in question.options" :key="option.label" class="question-option">
                <input
                  type="checkbox"
                  :checked="isMultiSelected(question.id, option.label)"
                  @change="toggleMultiAnswer(question.id, option.label, $event.target.checked)"
                />
                <span>{{ option.label }}</span>
              </label>
            </div>

            <textarea
              v-else
              :id="question.id"
              v-model="pendingAnswers[question.id]"
              rows="2"
              placeholder="请输入回答"
            />
          </div>
          <div class="question-panel__actions">
            <button class="btn-outline" type="button" :disabled="sending" @click="resetPendingQuestions">新开对话</button>
            <button class="btn" type="button" :disabled="sending || !canSubmitPendingAnswers" @click="submitPendingAnswers">继续执行</button>
          </div>
        </div>

        <div v-if="pendingAttachment" class="pending-attachment">
          <img :src="pendingAttachment.previewUrl" :alt="pendingAttachment.fileName" />
          <div class="pending-meta">
            <strong>{{ pendingAttachment.fileName }}</strong>
            <span>{{ formatFileSize(pendingAttachment.size) }} · 发送时安全上传</span>
          </div>
          <button class="btn-mini" type="button" :disabled="sending || uploading" @click="clearPendingAttachment">移除</button>
        </div>

        <textarea
          v-model="input"
          rows="4"
          placeholder="有问题尽管问，当前会话将保存到你的账号中（Enter 发送，Shift+Enter 换行）"
          :disabled="messagesLoading || (store.activeApp === 'manus' && pendingQuestions.length > 0)"
          @keydown="handleInputKeydown"
        />

        <div class="send-bar">
          <span>{{ sending ? 'AI 正在回复…' : uploading ? '图片上传中…' : store.currentSession ? '会话已保存' : '空白草稿' }}</span>
          <div class="send-actions">
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" class="file-input" @change="handleFileChange" />
            <button class="btn-outline" type="button" :disabled="sending || uploading" @click="triggerUpload">上传图片</button>
            <button class="btn" type="button" :disabled="sendDisabled" @click="send">发送</button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatMessage from '../components/ChatMessage.vue'
import { loveChatSync } from '../api/modules/ai'
import {
  createChatSession,
  createImageSession,
  listChatMessages,
  listChatSessions,
  uploadSessionAttachment,
} from '../api/modules/chat'
import { openFetchSSE } from '../api/sse'
import { API_BASE_URL } from '../api/constants'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { uid } from '../utils/uid'

const store = useAppStore()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()
const input = ref('')
const sending = ref(false)
const uploading = ref(false)
const messagesLoading = ref(false)
const pendingAttachment = ref(null)
const messageBox = ref(null)
const messages = ref([])
const fileInput = ref(null)
const pendingQuestions = ref([])
const pendingAnswers = ref({})
const pendingQuestionMessageId = ref('')
const pinnedTodo = ref(null)
let currentSSE = null
let sessionSearchTimer = null
let sessionRequestVersion = 0
let messageRequestVersion = 0

const modeOptions = computed(() => {
  if (store.activeApp === 'manus') {
    return [{ value: 'sse', label: '流式回复' }]
  }
  return [
    { value: 'sync', label: '同步回复' },
    { value: 'sse', label: '流式回复' },
    { value: 'sse_emitter', label: '事件流回复' },
  ]
})

const canSubmitPendingAnswers = computed(() => pendingQuestions.value.every((question) => {
  const answer = pendingAnswers.value[question.id]
  return Array.isArray(answer) ? answer.length > 0 : Boolean(String(answer || '').trim())
}))
const pinnedTodoCompleted = computed(() => pinnedTodo.value?.items?.filter((item) => item.status === 'completed').length || 0)
const sendDisabled = computed(() => sending.value
  || uploading.value
  || messagesLoading.value
  || (store.activeApp === 'manus' && pendingQuestions.value.length > 0)
  || (!input.value.trim() && !pendingAttachment.value))

watch(
  () => route.query.app,
  (app) => {
    if (!store.currentSession && (app === 'manus' || app === 'love')) store.setActiveApp(app)
  },
  { immediate: true },
)

watch(
  () => store.activeApp,
  (app) => {
    if (app === 'manus' && store.chatMode !== 'sse') store.setChatMode('sse')
  },
)

watch(
  () => messages.value.length,
  async () => {
    await nextTick()
    if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
  },
)

watch(
  () => authStore.currentAccount?.accountId,
  async (accountId, previousAccountId) => {
    if (accountId === previousAccountId) return
    clearConversationState()
    if (accountId && route.name === 'chat') await loadSessions({ reset: true, selectInitial: true })
  },
)

watch(
  () => store.accountStateVersion,
  () => clearConversationState(),
)

onMounted(async () => {
  await authStore.ensureCsrfToken()
  await loadSessions({ reset: true, selectInitial: true })
})

onBeforeUnmount(() => {
  currentSSE?.close()
  if (sessionSearchTimer) window.clearTimeout(sessionSearchTimer)
  revokeLocalPreviews()
})

/**
 * 从服务端按标题与稳定游标读取会话；搜索、刷新和账号变化会重置已有结果与游标。
 */
async function loadSessions({ append = false, reset = false, selectInitial = false } = {}) {
  if (!authStore.isAuthenticated || store.sessionsLoading) return
  const requestVersion = ++sessionRequestVersion
  if (reset) {
    store.sessions = []
    store.nextCursor = ''
    store.hasMoreSessions = false
  }
  store.sessionsLoading = true
  try {
    const response = await listChatSessions({
      keyword: store.sessionKeyword.trim() || undefined,
      cursor: append ? store.nextCursor || undefined : undefined,
    })
    if (requestVersion !== sessionRequestVersion) return
    const page = response.data || {}
    const incoming = Array.isArray(page.items) ? page.items : []
    const merged = append ? [...store.sessions, ...incoming] : incoming
    store.sessions = Array.from(new Map(merged.map((item) => [item.sessionId, item])).values())
    store.nextCursor = page.nextCursor || ''
    store.hasMoreSessions = Boolean(page.hasMore)
    store.addLog({
      endpoint: '/chat/sessions',
      status: 'success',
      elapsed: response.elapsed,
      message: `读取 ${incoming.length} 条会话`,
    })

    if (store.currentSession) {
      const refreshed = store.sessions.find((item) => item.sessionId === store.currentSession.sessionId)
      if (refreshed) store.selectSession(refreshed)
    } else if (selectInitial && !store.sessionKeyword.trim() && store.sessions.length) {
      const routeSession = store.sessions.find((item) => item.sessionId === route.query.sessionId)
      await switchSession(routeSession || store.sessions[0], { updateRoute: !routeSession })
    }
  } catch (error) {
    await handleSecurityError(error)
    authStore.showNotice(error.message || '会话列表加载失败')
  } finally {
    if (requestVersion === sessionRequestVersion) store.sessionsLoading = false
  }
}

/**
 * 搜索输入停止 320ms 后从首个游标重新请求服务端，避免在前端过滤消息正文。
 */
function scheduleSessionSearch() {
  if (sessionSearchTimer) window.clearTimeout(sessionSearchTimer)
  sessionSearchTimer = window.setTimeout(() => loadSessions({ reset: true }), 320)
}

/**
 * 选择会话时先清空旧消息和临时交互，再从服务端恢复完整历史，防止账号或会话内容闪现。
 */
async function switchSession(session, options = {}) {
  if (!session?.sessionId || sending.value) return
  const requestVersion = ++messageRequestVersion
  clearConversationState({ keepInput: false })
  store.selectSession(session)
  messagesLoading.value = true
  try {
    if (options.updateRoute !== false) {
      await router.replace({ query: { ...route.query, sessionId: session.sessionId, app: undefined } })
    }
    const response = await listChatMessages(session.sessionId)
    if (requestVersion !== messageRequestVersion) return
    messages.value = (Array.isArray(response.data) ? response.data : [])
      .map(mapHistoryMessage)
      .filter(Boolean)
    store.addLog({
      endpoint: `/chat/sessions/${session.sessionId}/messages`,
      status: 'success',
      elapsed: response.elapsed,
      message: `恢复 ${messages.value.length} 条可展示消息`,
    })
  } catch (error) {
    await handleSecurityError(error)
    authStore.showNotice(error.message || '会话历史加载失败')
  } finally {
    if (requestVersion === messageRequestVersion) messagesLoading.value = false
  }
}

/**
 * 新建按钮只产生空白草稿并移除地址中的正式会话标识，不调用任何写接口。
 */
async function newChat() {
  if (sending.value) return
  ++messageRequestVersion
  clearConversationState()
  store.startDraft()
  await router.replace({ query: route.query.app ? { app: route.query.app } : {} })
}

/**
 * 将数据库中的 Spring AI 或 Manus 结构化消息转换为现有消息组件能够稳定展示的结构。
 */
function mapHistoryMessage(record) {
  const payload = record?.payload || {}
  const type = String(payload.type || '').toUpperCase()
  const metadata = payload.metadata || {}
  const messageKind = metadata.messageKind || ''
  if (type === 'SYSTEM' || type === 'TOOL') return null

  const message = {
    id: `history-${record.id}`,
    role: type === 'USER' ? 'user' : 'assistant',
    content: payload.text || '',
    status: 'done',
    createTime: record.createTime,
  }
  if (metadata.contentUrl) {
    message.attachments = [{
      attachmentId: metadata.attachmentId,
      fileName: metadata.originalName || '会话图片',
      previewUrl: resolveAttachmentUrl(metadata.contentUrl),
    }]
  }
  if (messageKind === 'MANUS_TODO' && metadata.todo) {
    message.todoSnapshot = cloneTodoSnapshot(metadata.todo)
  } else if (messageKind === 'MANUS_QUESTION') {
    message.content = formatHistoricalQuestions(metadata.questions)
  } else if (messageKind === 'MANUS_ANSWER') {
    message.content = formatHistoricalAnswers(metadata.answers)
  } else if (messageKind === 'MANUS_STATUS') {
    message.content = formatHistoricalStatus(metadata.status, metadata.reason)
  }
  return message
}

/**
 * 首次发送时根据文本或纯图片路径创建服务端会话，并返回 AI 调用需要的附件标识。
 */
async function prepareSessionAndAttachment(question, attachment) {
  const appCode = store.activeApp === 'manus' ? 'MANUS' : 'LOVE_APP'
  let session = store.currentSession
  let uploadedAttachment = null

  if (!session && question) {
    const response = await createChatSession({ appCode, firstMessage: question })
    session = response.data
    store.upsertSession(session)
  }
  if (!session && attachment) {
    const response = await createImageSession(appCode, attachment.file)
    uploadedAttachment = response.data
    session = {
      sessionId: uploadedAttachment.sessionId,
      title: '图片会话',
      appCode,
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    }
    store.upsertSession(session)
  } else if (session && attachment) {
    const response = await uploadSessionAttachment(session.sessionId, attachment.file)
    uploadedAttachment = response.data
  }
  await router.replace({ query: { sessionId: session.sessionId } })
  return { session, uploadedAttachment }
}

/**
 * 完成会话创建、图片归属上传与 AI 请求。任何认证错误都会先清空敏感页面再引导重新登录。
 */
async function send() {
  if (sendDisabled.value) return
  const question = input.value.trim()
  const localAttachment = pendingAttachment.value
  const messageForAi = question || (localAttachment ? '请解释这张图片内容，并给出关键结论。' : '')
  sending.value = true
  uploading.value = Boolean(localAttachment)
  const start = Date.now()
  let assistant = null

  try {
    await authStore.ensureCsrfToken()
    const prepared = await prepareSessionAndAttachment(question, localAttachment)
    const attachment = prepared.uploadedAttachment ? {
      attachmentId: prepared.uploadedAttachment.attachmentId,
      fileName: prepared.uploadedAttachment.originalName || localAttachment.fileName,
      previewUrl: localAttachment.previewUrl,
      contentUrl: prepared.uploadedAttachment.contentUrl,
    } : null

    input.value = ''
    pendingAttachment.value = null
    const user = addMessage('user', question || '请解释这张图片', 'done', {
      attachments: attachment ? [attachment] : [],
    })
    assistant = addMessage('assistant', store.activeApp === 'manus' ? '正在分析任务…' : '', 'loading', {
      retryPayload: { question, attachment: null, app: store.activeApp, userId: user.id },
    })
    if (store.activeApp === 'manus') pendingQuestionMessageId.value = assistant.id
    clearPendingQuestions()
    uploading.value = false

    const params = buildAiParams(messageForAi, attachment)
    if (store.activeApp === 'love' && store.chatMode === 'sync') {
      const response = await loveChatSync(params)
      patchMessage(assistant.id, { content: normalizeAssistantText(response.data), status: 'done' })
      store.addLog({ endpoint: '/ai/love_app/chat/sync', status: 'success', elapsed: response.elapsed })
    } else {
      const endpoint = store.activeApp === 'manus'
        ? '/ai/manus/chat'
        : (store.chatMode === 'sse' ? '/ai/love_app/chat/sse' : '/ai/love_app/chat/sse_emitter')
      await sendViaSSE({
        messageId: assistant.id,
        endpoint,
        params,
        timeoutMs: store.activeApp === 'manus' ? 240000 : 45000,
        typewriter: store.activeApp === 'manus',
        onQuestion: handlePendingQuestion,
        onTodo: handleTodoUpdate,
      })
      store.addLog({ endpoint, status: 'success', elapsed: Date.now() - start })
    }
    await loadSessions({ reset: true })
  } catch (error) {
    if (assistant) patchMessage(assistant.id, { content: error.message || '请求失败', status: 'error' })
    store.addLog({
      endpoint: store.activeApp === 'manus' ? '/ai/manus/chat' : store.chatMode,
      status: 'error',
      elapsed: Date.now() - start,
      message: error.message,
    })
    await handleSecurityError(error)
    if (!assistant) authStore.showNotice(error.message || '发送失败，请重试')
  } finally {
    uploading.value = false
    sending.value = false
  }
}

/**
 * 通过携带 Cookie 与 CSRF Token 的 Fetch 建立 POST 事件流，并统一处理文本、追问、Todo 和完成事件。
 */
function sendViaSSE({ messageId, endpoint, params, timeoutMs = 45000, typewriter = false, onQuestion, onTodo, opener }) {
  return new Promise((resolve, reject) => {
    let finalText = ''
    let settled = false
    let receivedQuestion = false
    let timeoutId = null
    let timerId = null
    const queue = []

    const clearTimer = () => {
      if (timerId) {
        window.clearInterval(timerId)
        timerId = null
      }
    }
    const clearIdleTimeout = () => {
      if (timeoutId) {
        window.clearTimeout(timeoutId)
        timeoutId = null
      }
    }
    const refreshIdleTimeout = () => {
      if (timeoutMs <= 0) return
      clearIdleTimeout()
      timeoutId = window.setTimeout(() => {
        if (settled) return
        settled = true
        while (queue.length) finalText += queue.shift()
        clearTimer()
        currentSSE?.close()
        if (receivedQuestion) {
          patchMessage(messageId, { status: 'done' })
          resolve({ receivedQuestion: true })
        } else if (finalText) {
          patchMessage(messageId, { content: finalText, status: 'done' })
          resolve({ receivedQuestion: false })
        } else {
          reject(new Error('SSE 长时间无响应，请重试'))
        }
      }, timeoutMs)
    }
    const startTypewriter = () => {
      if (timerId) return
      timerId = window.setInterval(() => {
        if (!queue.length) {
          if (settled) clearTimer()
          return
        }
        finalText += queue.splice(0, 12).join('')
        patchMessage(messageId, { content: finalText, status: 'loading' })
      }, 12)
    }

    currentSSE?.close()
    currentSSE = (opener || openFetchSSE)({
      path: endpoint,
      method: 'POST',
      params,
      onOpen: refreshIdleTimeout,
      onMessage: (chunk) => {
        refreshIdleTimeout()
        if (typewriter) {
          queue.push(...chunk.split(''))
          startTypewriter()
        } else {
          finalText += chunk
          patchMessage(messageId, { content: finalText, status: 'loading' })
        }
      },
      onQuestion: (raw) => {
        refreshIdleTimeout()
        receivedQuestion = true
        onQuestion?.(parseEventPayload(raw))
      },
      onTodo: (raw) => {
        refreshIdleTimeout()
        onTodo?.(parseEventPayload(raw))
      },
      onDone: () => {
        if (settled) return
        settled = true
        clearIdleTimeout()
        if (typewriter && queue.length) finalText += queue.join('')
        queue.length = 0
        clearTimer()
        if (receivedQuestion && !finalText) {
          patchMessage(messageId, { status: 'done' })
          resolve({ receivedQuestion: true })
          return
        }
        patchMessage(messageId, { content: finalText || '(empty)', status: 'done' })
        archivePinnedTodo()
        resolve({ receivedQuestion })
      },
      onError: (error) => {
        if (settled) return
        settled = true
        clearIdleTimeout()
        clearTimer()
        if (receivedQuestion) {
          patchMessage(messageId, { status: 'done' })
          resolve({ receivedQuestion: true })
          return
        }
        reject(error?.status ? error : new Error(error?.message || 'SSE 连接失败，请检查后端接口'))
      },
    })
    refreshIdleTimeout()
  })
}

/**
 * 提交 Manus 补充答案时使用 JSON POST 事件流，原请求失败后恢复页面上的待答内容。
 */
async function submitPendingAnswers() {
  if (!pendingQuestions.value.length || sending.value) return
  const previousQuestions = pendingQuestions.value
  const previousAnswers = pendingAnswers.value
  const answerMap = Object.fromEntries(
    Object.entries(pendingAnswers.value).map(([key, value]) => [key, Array.isArray(value) ? value.join(', ') : value]),
  )
  const assistantId = pendingQuestionMessageId.value || addMessage('assistant', '已收到补充信息，正在继续处理…', 'loading').id
  patchMessage(assistantId, { content: '已收到补充信息，正在继续处理…', status: 'loading' })
  sending.value = true
  pendingQuestions.value = []
  pendingAnswers.value = {}

  try {
    await authStore.ensureCsrfToken()
    const result = await sendViaSSE({
      messageId: assistantId,
      endpoint: '/ai/manus/chat/continue',
      timeoutMs: 240000,
      typewriter: true,
      onQuestion: handlePendingQuestion,
      onTodo: handleTodoUpdate,
      opener: ({ path, onOpen, onMessage, onQuestion, onTodo, onDone, onError }) => openFetchSSE({
        path,
        method: 'POST',
        body: { chatId: store.currentChatId, answers: answerMap },
        onOpen,
        onMessage,
        onQuestion,
        onTodo,
        onDone,
        onError,
      }),
    })
    if (!result?.receivedQuestion) clearPendingQuestions()
    await loadSessions({ reset: true })
  } catch (error) {
    pendingQuestions.value = previousQuestions
    pendingAnswers.value = previousAnswers
    pendingQuestionMessageId.value = assistantId
    patchMessage(assistantId, { content: error.message || '继续执行失败', status: 'error' })
    await handleSecurityError(error)
  } finally {
    sending.value = false
  }
}

function normalizeAssistantText(raw) {
  if (raw == null) return ''
  if (typeof raw === 'string') return raw
  if (typeof raw === 'object') return raw.content || raw.text || raw.question || raw.contentUrl || JSON.stringify(raw)
  return String(raw)
}

function addMessage(role, content, status = 'done', extra = {}) {
  const item = { id: uid('msg'), role, content, status, ...extra }
  messages.value.push(item)
  return item
}

function patchMessage(id, patch) {
  const index = messages.value.findIndex((item) => item.id === id)
  if (index >= 0) messages.value[index] = { ...messages.value[index], ...patch }
}

function retryMessage(message) {
  if (!message.retryPayload || sending.value) return
  input.value = message.retryPayload.question || ''
  if (message.retryPayload.app) store.setActiveApp(message.retryPayload.app)
  send()
}

function buildAiParams(message, attachment) {
  const params = { message, chatId: store.currentChatId }
  if (attachment?.attachmentId) {
    params.option = 'image'
    params.attachmentId = attachment.attachmentId
  }
  return params
}

function parseEventPayload(raw) {
  if (typeof raw !== 'string') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

function cloneTodoSnapshot(snapshot) {
  if (!snapshot) return null
  return { items: Array.isArray(snapshot.items) ? snapshot.items.map((item) => ({ ...item })) : [] }
}

function archivePinnedTodo() {
  if (!pinnedTodo.value?.items?.length) return
  addMessage('assistant', '', 'done', { todoSnapshot: cloneTodoSnapshot(pinnedTodo.value) })
  pinnedTodo.value = null
}

function handlePendingQuestion(payload) {
  const questions = Array.isArray(payload?.questions) ? payload.questions : []
  pendingQuestions.value = questions
  pendingAnswers.value = Object.fromEntries(questions.map((question) => [question.id, question.multiSelect ? [] : '']))
  const messageId = pendingQuestionMessageId.value || addMessage('assistant', '请先回答以下问题后继续。', 'done').id
  pendingQuestionMessageId.value = messageId
  patchMessage(messageId, { content: '请先回答以下问题后继续。', status: 'done' })
}

function handleTodoUpdate(payload) {
  const snapshot = payload?.todo
  if (snapshot && Array.isArray(snapshot.items)) pinnedTodo.value = cloneTodoSnapshot(snapshot)
}

function clearPendingQuestions() {
  pendingQuestions.value = []
  pendingAnswers.value = {}
  pendingQuestionMessageId.value = ''
}

function resetPendingQuestions() {
  clearPendingQuestions()
  if (store.activeApp === 'manus') newChat()
}

function isMultiSelected(questionId, label) {
  const current = pendingAnswers.value[questionId]
  return Array.isArray(current) && current.includes(label)
}

function toggleMultiAnswer(questionId, label, checked) {
  const current = Array.isArray(pendingAnswers.value[questionId]) ? [...pendingAnswers.value[questionId]] : []
  pendingAnswers.value = {
    ...pendingAnswers.value,
    [questionId]: checked ? [...current, label] : current.filter((item) => item !== label),
  }
}

function handleInputKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

async function copyText(text) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    authStore.showNotice('内容已复制')
  } catch {
    authStore.showNotice('复制失败，请手动选择内容')
  }
}

function triggerUpload() {
  fileInput.value?.click()
}

/**
 * 选择图片时先执行与后端一致的格式和大小预校验，仅创建本地预览，不提前创建服务端会话。
 */
function handleFileChange(event) {
  const file = event.target?.files?.[0]
  event.target.value = ''
  if (!file || sending.value) return
  const allowedTypes = new Set(['image/jpeg', 'image/png', 'image/webp'])
  const extensionAllowed = /\.(jpe?g|png|webp)$/i.test(file.name)
  if (!allowedTypes.has(file.type) || !extensionAllowed) {
    authStore.showNotice('仅支持 JPEG、PNG、WebP 图片')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    authStore.showNotice('单张图片不能超过 10 MB')
    return
  }
  clearPendingAttachment()
  pendingAttachment.value = {
    file,
    fileName: file.name,
    previewUrl: URL.createObjectURL(file),
    mimeType: file.type,
    size: file.size,
  }
}

function clearPendingAttachment() {
  if (pendingAttachment.value?.previewUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(pendingAttachment.value.previewUrl)
  }
  pendingAttachment.value = null
}

/**
 * 清空会话级消息、附件、Todo 与待答问题；本方法不会清空左侧服务端会话列表。
 */
function clearConversationState({ keepInput = false } = {}) {
  currentSSE?.close()
  currentSSE = null
  revokeLocalPreviews()
  messages.value = []
  pinnedTodo.value = null
  clearPendingQuestions()
  clearPendingAttachment()
  if (!keepInput) input.value = ''
}

function revokeLocalPreviews() {
  messages.value.forEach((message) => {
    message.attachments?.forEach((attachment) => {
      if (attachment.previewUrl?.startsWith('blob:')) URL.revokeObjectURL(attachment.previewUrl)
    })
  })
}

/**
 * 统一处理请求的 401/403：401 清空账号数据并返回 Landing；403 只重签 Token 和复核身份，不重放写请求。
 */
async function handleSecurityError(error) {
  if (error?.status === 401) {
    authStore.expireAuthentication(route.fullPath)
    await router.replace('/')
  } else if (error?.status === 403) {
    await authStore.revalidateAfterForbidden()
  }
}

function resolveAttachmentUrl(contentUrl) {
  if (!contentUrl) return ''
  if (/^https?:\/\//i.test(contentUrl)) return contentUrl
  return `${API_BASE_URL}${contentUrl.startsWith('/') ? contentUrl : `/${contentUrl}`}`
}

function formatHistoricalQuestions(questions) {
  if (!Array.isArray(questions) || !questions.length) return '历史补充问题（只读）'
  return ['历史补充问题（只读）', ...questions.map((item, index) => `${index + 1}. ${item.question || item.header || '待补充信息'}`)].join('\n')
}

function formatHistoricalAnswers(answers) {
  const entries = Object.entries(answers || {})
  return entries.length ? ['已提交的补充信息', ...entries.map(([key, value]) => `${key}：${value}`)].join('\n') : '已提交补充信息'
}

function formatHistoricalStatus(status, reason) {
  const labels = { COMPLETED: '任务已完成', FAILED: '任务执行失败', INTERRUPTED: '任务已中断' }
  return [labels[status] || `任务状态：${status || '未知'}`, reason].filter(Boolean).join('\n')
}

function formatTodoStatus(status) {
  if (status === 'completed') return '已完成'
  if (status === 'in_progress') return '进行中'
  return '待处理'
}

function formatAppName(appCode) {
  if (appCode === 'MANUS') return 'Manus'
  if (appCode === 'TODO_DEMO') return 'Todo'
  return 'Love App'
}

function formatSessionTime(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) {
    return `今天 ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}`
  }
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

function formatFileSize(size) {
  if (!size) return '0 KB'
  return size >= 1024 * 1024 ? `${(size / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(size / 1024)} KB`
}
</script>

<style scoped>
.chat-page {
  display: grid;
  grid-template-columns: 370px minmax(0, 1fr);
  gap: 26px;
  min-height: calc(100vh - 138px);
}

.session-sidebar,
.chat-main {
  border: 1px solid var(--border);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-soft);
  overflow: hidden;
}

.session-sidebar {
  display: flex;
  flex-direction: column;
  padding: 20px;
}

.session-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.session-heading h2,
.chat-topbar h2 {
  font-size: 26px;
}

.session-create {
  padding: 10px 17px;
}

.session-search {
  margin-top: 20px;
  display: grid;
  grid-template-columns: 34px 1fr;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: #fff;
  padding: 0 14px;
}

.session-search:focus-within {
  outline: 3px solid rgba(240, 128, 98, 0.16);
  border-color: var(--primary);
}

.session-search span {
  position: relative;
  width: 19px;
  height: 19px;
  border: 2px solid #aa8c79;
  border-radius: 50%;
}

.session-search span::after {
  content: '';
  position: absolute;
  width: 8px;
  height: 2px;
  right: -6px;
  bottom: -3px;
  background: #aa8c79;
  transform: rotate(45deg);
}

.session-search input {
  border: 0;
  outline: 0;
  padding: 14px 6px;
}

.session-list {
  min-height: 260px;
  max-height: calc(100vh - 390px);
  overflow-y: auto;
  margin-top: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.session-item {
  width: 100%;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.88);
  color: var(--text-main);
  padding: 16px 14px;
  text-align: left;
  cursor: pointer;
}

.session-item.active {
  border-color: var(--primary);
  background: #fff2e8;
}

.session-item:disabled {
  cursor: not-allowed;
}

.session-status {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #f8cea3;
  color: #fff;
  font-weight: 900;
}

.session-item.active .session-status {
  background: var(--primary);
}

.session-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.session-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
}

.session-copy small {
  color: var(--text-subtle);
}

.session-empty {
  padding: 26px 10px;
  color: var(--text-subtle);
  text-align: center;
}

.load-more {
  margin-top: 2px;
}

.session-owner-note {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 9px;
  border-radius: 14px;
  background: #f7f3eb;
  color: var(--text-subtle);
  padding: 13px;
  font-size: 13px;
}

.session-owner-note span {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--mint);
  color: #fff;
}

.chat-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-topbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  border-bottom: 1px solid var(--border);
  padding: 26px 40px;
}

.account-context {
  width: fit-content;
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 10px;
  border-radius: 999px;
  background: #fff0e5;
  color: var(--text-subtle);
  padding: 6px 14px;
  font-size: 13px;
}

.account-context span {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--mint);
}

.top-actions {
  display: flex;
  gap: 10px;
}

.top-actions select {
  min-width: 160px;
  font-weight: 700;
}

.message-list {
  min-height: 360px;
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 38px 62px;
}

.message-list :deep(.chat-message.is-user) {
  width: min(570px, 88%);
  align-self: flex-start;
}

.message-list :deep(.chat-message.is-assistant) {
  width: min(890px, 94%);
  align-self: flex-end;
}

.empty-tip {
  margin: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--text-subtle);
  text-align: center;
}

.empty-tip strong {
  color: var(--text-main);
  font-size: 19px;
}

.chat-input-wrap {
  border-top: 1px solid var(--border);
  padding: 20px 40px 24px;
  background: rgba(255, 255, 255, 0.7);
}

.todo-pin,
.question-panel {
  margin-bottom: 12px;
  border: 1px solid rgba(240, 128, 98, 0.3);
  border-radius: 17px;
  background: linear-gradient(180deg, #fff9f3, #fff0e4);
  padding: 14px;
}

.todo-pin__header,
.question-panel__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-subtle);
  font-size: 13px;
}

.todo-pin__header p {
  margin-top: 4px;
}

.todo-pin__list,
.question-panel,
.question-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.todo-pin__item {
  display: grid;
  grid-template-columns: 18px 1fr auto;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #fff;
  padding: 9px 11px;
}

.todo-pin__item.is-completed {
  background: #f2faf5;
}

.todo-pin__icon {
  color: var(--primary-deep);
  font-weight: 800;
}

.todo-pin__status,
.todo-pin__hint {
  color: var(--text-subtle);
  font-size: 12px;
}

.question-item label {
  color: var(--primary-deep);
  font-size: 13px;
  font-weight: 800;
}

.question-item__options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.question-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #fff;
  padding: 7px 10px;
}

.question-option input {
  width: auto;
}

.question-panel__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.pending-attachment {
  margin-bottom: 10px;
  display: flex;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: #fff8f0;
  padding: 10px;
}

.pending-attachment img {
  width: 64px;
  height: 64px;
  border-radius: 12px;
  object-fit: cover;
}

.pending-meta {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.pending-meta strong,
.pending-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-meta span {
  color: var(--text-subtle);
  font-size: 12px;
}

.chat-input-wrap > textarea {
  min-height: 96px;
  resize: vertical;
  border-radius: 18px;
  padding: 16px;
}

.send-bar {
  margin-top: 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  color: var(--text-subtle);
  font-size: 13px;
}

.send-actions {
  display: flex;
  gap: 10px;
}

.file-input {
  display: none;
}

@media (max-width: 1100px) {
  .chat-page {
    grid-template-columns: 300px minmax(0, 1fr);
  }

  .message-list {
    padding: 28px;
  }
}

@media (max-width: 820px) {
  .chat-page {
    grid-template-columns: 1fr;
  }

  .session-list {
    max-height: 300px;
  }

  .chat-topbar,
  .send-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .top-actions,
  .send-actions {
    width: 100%;
  }

  .top-actions select,
  .send-actions button {
    flex: 1;
    min-width: 0;
  }

  .chat-input-wrap,
  .chat-topbar,
  .message-list {
    padding-left: 18px;
    padding-right: 18px;
  }
}
</style>
