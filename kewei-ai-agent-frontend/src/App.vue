<template>
  <div>
    <nav class="top-nav">
      <div class="top-nav-inner">
        <div class="brand">Kewei AI Agent</div>
        <div class="nav-links">
          <RouterLink class="nav-link" to="/">Landing</RouterLink>
          <RouterLink class="nav-link" to="/chat">Chat</RouterLink>
          <RouterLink v-if="authStore.isAdmin" class="nav-link" to="/console">Console</RouterLink>
          <template v-if="!authStore.isAuthenticated">
            <button class="nav-auth-button is-secondary" type="button" @click="authStore.openAuthModal('login')">登录</button>
            <button class="nav-auth-button is-primary" type="button" @click="authStore.openAuthModal('register')">注册</button>
          </template>
          <template v-else>
            <div class="account-pill" :title="authStore.currentAccount.account">
              <span class="account-avatar">{{ accountInitial }}</span>
              <span class="account-name">{{ authStore.currentAccount.account }}</span>
              <span aria-hidden="true">⌄</span>
            </div>
            <button class="nav-auth-button is-secondary" type="button" :disabled="loggingOut" @click="handleLogout">
              {{ loggingOut ? '退出中…' : '退出登录' }}
            </button>
          </template>
        </div>
      </div>
    </nav>

    <main class="main-shell" :class="{ 'is-chat-shell': route.name === 'chat' }">
      <RouterView />
    </main>

    <AuthModal @authenticated="handleAuthenticated" />
    <Transition name="toast">
      <div v-if="authStore.notice" class="global-toast" role="status">{{ authStore.notice }}</div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'
import AuthModal from './components/AuthModal.vue'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const loggingOut = ref(false)
const accountInitial = computed(() => Array.from(authStore.currentAccount?.account || 'K')[0]?.toUpperCase() || 'K')

/**
 * 认证成功后消费受限路由目标；Console 目标仍根据后端返回的最新角色进行二次校验。
 */
async function handleAuthenticated(account) {
  const target = authStore.consumePendingTarget()
  if (!target) return
  if (target.startsWith('/console') && account.role !== 'ADMIN') {
    authStore.showNotice('当前账号无 Console 访问权限')
    await router.replace('/')
    return
  }
  await router.push(target)
}

/**
 * 退出当前设备会话并跳回 Landing；失败时保留当前页面状态并展示后端错误信息。
 */
async function handleLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await authStore.logout()
    await router.replace('/')
  } catch (error) {
    if (error.status === 403) await authStore.revalidateAfterForbidden()
    authStore.showNotice(error.message || '退出登录失败')
  } finally {
    loggingOut.value = false
  }
}
</script>
