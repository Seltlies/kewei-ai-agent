import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const activeApp = ref('love')
  const chatMode = ref('sync')
  const sessions = ref([])
  const currentSession = ref(null)
  const sessionKeyword = ref('')
  const nextCursor = ref('')
  const hasMoreSessions = ref(false)
  const sessionsLoading = ref(false)
  const accountStateVersion = ref(0)
  const logs = ref([])
  const currentChatId = computed(() => currentSession.value?.sessionId || '')

  function setActiveApp(app) {
    activeApp.value = app
  }

  function setChatMode(mode) {
    chatMode.value = mode
  }

  /**
   * 选择服务端正式会话，并依据会话应用同步页面应用入口。
   */
  function selectSession(session) {
    currentSession.value = session || null
    if (session?.appCode === 'MANUS') activeApp.value = 'manus'
    if (session?.appCode === 'LOVE_APP') activeApp.value = 'love'
  }

  /**
   * 新建操作只清空当前正式会话，保留所选应用形成空白草稿，不向服务端写入数据。
   */
  function startDraft() {
    currentSession.value = null
  }

  /**
   * 将刚创建或更新的会话放到列表首位，保证当前会话状态与左侧列表同步。
   */
  function upsertSession(session) {
    if (!session?.sessionId) return
    sessions.value = [session, ...sessions.value.filter((item) => item.sessionId !== session.sessionId)]
    selectSession(session)
  }

  /**
   * 账号退出、切换或认证失效时清空全部账号相关页面状态，日志也不跨账号保留。
   */
  function resetAccountState() {
    sessions.value = []
    currentSession.value = null
    sessionKeyword.value = ''
    nextCursor.value = ''
    hasMoreSessions.value = false
    sessionsLoading.value = false
    logs.value = []
    accountStateVersion.value += 1
  }

  function addLog(log) {
    logs.value.unshift({
      id: `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
      time: new Date().toLocaleTimeString(),
      ...log,
    })
    logs.value = logs.value.slice(0, 80)
  }

  function clearLogs() {
    logs.value = []
  }

  return {
    activeApp,
    chatMode,
    sessions,
    currentSession,
    currentChatId,
    sessionKeyword,
    nextCursor,
    hasMoreSessions,
    sessionsLoading,
    accountStateVersion,
    logs,
    setActiveApp,
    setChatMode,
    selectSession,
    startDraft,
    upsertSession,
    resetAccountState,
    addLog,
    clearLogs,
  }
})
