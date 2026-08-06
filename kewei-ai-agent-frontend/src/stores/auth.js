import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCsrf, getCurrentAccount, loginAccount, logoutAccount, registerAccount } from '../api/modules/auth'
import { clearCsrfToken, hasCsrfToken, setCsrfToken } from '../api/csrf'
import { useAppStore } from './app'

export const useAuthStore = defineStore('auth', () => {
  const currentAccount = ref(null)
  const initialized = ref(false)
  const initializing = ref(false)
  const modalOpen = ref(false)
  const modalMode = ref('login')
  const pendingTarget = ref('')
  const modalReason = ref('')
  const notice = ref('')
  const csrfReady = ref(false)
  let initializePromise = null
  let noticeTimer = null

  const isAuthenticated = computed(() => Boolean(currentAccount.value))
  const isAdmin = computed(() => currentAccount.value?.role === 'ADMIN')

  /**
   * 应用启动时只恢复服务端 Session 中的当前账号。未登录的 401 是正常访客状态，
   * 不主动弹出登录框；真正进入受限路由时再由路由守卫决定交互。
   */
  async function initialize() {
    if (initialized.value) return currentAccount.value
    if (initializePromise) return initializePromise
    initializing.value = true
    initializePromise = (async () => {
      try {
        const response = await getCurrentAccount()
        currentAccount.value = response.data
        await refreshCsrfToken()
      } catch (error) {
        if (error.status !== 401) {
          showNotice(error.message || '登录状态恢复失败')
        }
        currentAccount.value = null
        clearCsrfToken()
        csrfReady.value = false
      } finally {
        initialized.value = true
        initializing.value = false
        initializePromise = null
      }
      return currentAccount.value
    })()
    return initializePromise
  }

  /**
   * 获取并缓存当前 Session 的 CSRF Token。登录、注册导致 Session ID 轮换后会强制重新获取。
   */
  async function refreshCsrfToken() {
    clearCsrfToken()
    csrfReady.value = false
    const response = await getCsrf()
    setCsrfToken(response.data)
    csrfReady.value = hasCsrfToken()
    return csrfReady.value
  }

  /**
   * 在修改请求前确保已经取得 CSRF Token，避免页面并发触发多次无效写请求。
   */
  async function ensureCsrfToken() {
    if (hasCsrfToken()) {
      csrfReady.value = true
      return true
    }
    return refreshCsrfToken()
  }

  /**
   * 打开登录或注册弹窗，并记录受限页面的原目标地址供认证成功后继续访问。
   */
  function openAuthModal(mode = 'login', options = {}) {
    modalMode.value = mode === 'register' ? 'register' : 'login'
    pendingTarget.value = options.target || ''
    modalReason.value = options.reason || ''
    modalOpen.value = true
  }

  /**
   * 在同一弹窗中切换登录和注册，具体表单负责保留账号并清空密码字段。
   */
  function switchModalMode(mode) {
    modalMode.value = mode === 'register' ? 'register' : 'login'
  }

  /**
   * 关闭认证弹窗并取消待续跳路由，访客继续停留在当前公开页面。
   */
  function closeAuthModal() {
    modalOpen.value = false
    pendingTarget.value = ''
    modalReason.value = ''
  }

  /**
   * 统一执行登录或注册。成功后立即丢弃登录前 Token 并获取新 Session 的 Token，
   * 同时在账号发生变化时先清空上一账号的会话页面状态。
   */
  async function authenticate(mode, payload) {
    await ensureCsrfToken()
    const response = mode === 'register'
      ? await registerAccount(payload)
      : await loginAccount(payload)
    const previousAccountId = currentAccount.value?.accountId
    if (previousAccountId !== response.data?.accountId) {
      useAppStore().resetAccountState()
    }
    currentAccount.value = response.data
    await refreshCsrfToken()
    modalOpen.value = false
    console.info('[认证] 账号认证成功', {
      accountId: response.data?.accountId,
      role: response.data?.role,
      mode,
    })
    return response.data
  }

  /**
   * 注销当前设备前先清空账号页面数据，成功后移除内存 Token 和待续跳状态。
   */
  async function logout() {
    const accountId = currentAccount.value?.accountId
    // 请求发出前先清空会话页面，避免退出过程或账号切换期间继续展示旧账号内容。
    useAppStore().resetAccountState()
    await ensureCsrfToken()
    await logoutAccount()
    clearAuthenticatedState()
    console.info('[认证] 当前设备退出登录成功', { accountId })
    showNotice('已安全退出当前账号')
  }

  /**
   * 认证失效或退出时清空所有账号相关前端状态，防止旧账号内容短暂泄漏给下一个账号。
   */
  function clearAuthenticatedState() {
    useAppStore().resetAccountState()
    currentAccount.value = null
    clearCsrfToken()
    csrfReady.value = false
    pendingTarget.value = ''
    modalReason.value = ''
  }

  /**
   * 请求返回未认证时统一清理敏感页面状态并记录原目标，随后由调用页面跳回 Landing。
   */
  function expireAuthentication(target = '/chat') {
    clearAuthenticatedState()
    openAuthModal('login', { target, reason: 'session-expired' })
    showNotice('登录状态已失效，请重新登录')
  }

  /**
   * 修改请求遭遇 403 时重新签发 Token 并核对账号身份，但绝不自动重放原写请求。
   */
  async function revalidateAfterForbidden() {
    try {
      await refreshCsrfToken()
      const response = await getCurrentAccount()
      currentAccount.value = response.data
    } catch (error) {
      if (error.status === 401) {
        expireAuthentication(window.location.pathname + window.location.search)
      }
    }
  }

  /**
   * 读取并清空认证前记录的目标路由，确保一次认证成功最多只触发一次后续跳转。
   */
  function consumePendingTarget() {
    const target = pendingTarget.value
    pendingTarget.value = ''
    modalReason.value = ''
    return target
  }

  /**
   * 展示全局轻提示，连续提示时以最新内容为准并重新计算关闭时间。
   */
  function showNotice(message) {
    notice.value = message
    if (noticeTimer) window.clearTimeout(noticeTimer)
    noticeTimer = window.setTimeout(() => {
      notice.value = ''
      noticeTimer = null
    }, 3600)
  }

  return {
    currentAccount,
    initialized,
    initializing,
    modalOpen,
    modalMode,
    pendingTarget,
    modalReason,
    notice,
    csrfReady,
    isAuthenticated,
    isAdmin,
    initialize,
    refreshCsrfToken,
    ensureCsrfToken,
    openAuthModal,
    switchModalMode,
    closeAuthModal,
    authenticate,
    logout,
    clearAuthenticatedState,
    expireAuthentication,
    revalidateAfterForbidden,
    consumePendingTarget,
    showNotice,
  }
})
