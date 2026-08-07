<template>
  <section class="account-management-card">
    <header class="account-management-header">
      <div>
        <h2>账号管理</h2>
        <p>管理平台账号角色与可用状态，不提供密码和聊天内容查看能力。</p>
      </div>
      <strong>共 {{ accountPage.total }} 个账号</strong>
    </header>

    <div class="account-filters">
      <label class="account-search">
        <span class="search-icon" aria-hidden="true" />
        <input
          v-model="filters.keyword"
          type="search"
          placeholder="搜索账号"
          aria-label="搜索账号"
          @input="scheduleSearch"
          @keydown.enter.prevent="applyFilters"
        />
      </label>
      <label>
        <span class="sr-only">角色筛选</span>
        <select v-model="filters.role" aria-label="角色筛选" @change="applyFilters">
          <option value="">全部角色</option>
          <option value="USER">普通用户</option>
          <option value="ADMIN">admin</option>
        </select>
      </label>
      <label>
        <span class="sr-only">状态筛选</span>
        <select v-model="filters.status" aria-label="状态筛选" @change="applyFilters">
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="DISABLED">已停用</option>
        </select>
      </label>
    </div>

    <div class="account-table-wrap" :aria-busy="loading">
      <table class="account-table">
        <thead>
          <tr>
            <th>账号</th>
            <th>角色</th>
            <th>状态</th>
            <th>注册时间</th>
            <th>最后登录时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody v-if="accountPage.items.length">
          <tr v-for="account in accountPage.items" :key="account.accountId">
            <td>
              <div class="account-identity">
                <span class="account-list-avatar">{{ accountInitial(account.account) }}</span>
                <div>
                  <strong :title="account.account">{{ account.account }}</strong>
                  <small v-if="account.currentAccount">当前账号</small>
                </div>
              </div>
            </td>
            <td>
              <span class="role-badge" :class="{ 'is-admin': account.role === 'ADMIN' }">
                {{ roleLabel(account.role) }}
              </span>
            </td>
            <td>
              <span class="status-label" :class="{ 'is-disabled': account.status === 'DISABLED' }">
                <i aria-hidden="true" />
                {{ statusLabel(account.status) }}
              </span>
            </td>
            <td>{{ formatTime(account.registerTime) }}</td>
            <td>{{ account.lastLoginTime ? formatTime(account.lastLoginTime) : '从未登录' }}</td>
            <td>
              <div v-if="account.currentAccount" class="self-operation">不可调整自己的账号</div>
              <div v-else class="account-actions">
                <button class="account-action-button" type="button" @click="openRoleConfirmation(account)">
                  调整角色
                </button>
                <button
                  class="account-action-button"
                  :class="account.status === 'ACTIVE' ? 'is-disable' : 'is-enable'"
                  type="button"
                  @click="openStatusConfirmation(account)"
                >
                  {{ account.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="loading" class="account-table-state">正在加载账号…</div>
      <div v-else-if="!accountPage.items.length" class="account-table-state">暂无符合条件的账号</div>
    </div>

    <footer class="account-pagination">
      <span>{{ pageRangeText }}</span>
      <div>
        <button type="button" :disabled="loading || accountPage.page <= 1" @click="changePage(accountPage.page - 1)">‹</button>
        <span>第 {{ accountPage.totalPages ? accountPage.page : 0 }} / {{ accountPage.totalPages }} 页</span>
        <button
          type="button"
          :disabled="loading || accountPage.page >= accountPage.totalPages"
          @click="changePage(accountPage.page + 1)"
        >
          ›
        </button>
      </div>
    </footer>
  </section>

  <Teleport to="body">
    <div v-if="pendingAction" class="confirm-overlay" role="presentation" @mousedown.self="closeConfirmation">
      <section class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="account-confirm-title">
        <span class="confirm-mark" aria-hidden="true">!</span>
        <h3 id="account-confirm-title">{{ confirmationTitle }}</h3>
        <p>{{ confirmationDescription }}</p>
        <p v-if="pendingAction.type === 'status' && pendingAction.nextValue === 'DISABLED'" class="confirm-warning">
          该账号退出后将无法再次登录，当前已建立的登录会话不会被主动中断。
        </p>
        <div class="confirm-actions">
          <button class="btn-outline" type="button" :disabled="submitting" @click="closeConfirmation">取消</button>
          <button class="btn" type="button" :disabled="submitting" @click="confirmAction">
            {{ submitting ? '提交中…' : '确认修改' }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  listAdminAccounts,
  updateAdminAccountRole,
  updateAdminAccountStatus,
} from '../api/modules/admin'
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const filters = reactive({ keyword: '', role: '', status: '' })
const accountPage = reactive({ items: [], page: 1, size: 20, total: 0, totalPages: 0 })
const loading = ref(false)
const submitting = ref(false)
const pendingAction = ref(null)
let searchTimer = null
let requestVersion = 0

const pageRangeText = computed(() => {
  if (!accountPage.total) return '0 / 0'
  const start = (accountPage.page - 1) * accountPage.size + 1
  const end = Math.min(accountPage.page * accountPage.size, accountPage.total)
  return `${start}–${end} / ${accountPage.total}`
})
const confirmationTitle = computed(() => pendingAction.value?.type === 'role' ? '确认调整账号角色' : '确认调整账号状态')
const confirmationDescription = computed(() => {
  const action = pendingAction.value
  if (!action) return ''
  if (action.type === 'role') {
    return `将账号“${action.account.account}”的角色调整为“${roleLabel(action.nextValue)}”。`
  }
  return `将账号“${action.account.account}”调整为“${statusLabel(action.nextValue)}”状态。`
})

onMounted(() => loadAccounts())

onBeforeUnmount(() => {
  if (searchTimer) window.clearTimeout(searchTimer)
  requestVersion += 1
})

/**
 * 查询账号页并忽略晚到的旧请求；若筛选变化后当前页超过新总页数，则回到最后一个有效页。
 */
async function loadAccounts() {
  const currentVersion = ++requestVersion
  loading.value = true
  try {
    const response = await listAdminAccounts({
      keyword: filters.keyword.trim() || undefined,
      role: filters.role || undefined,
      status: filters.status || undefined,
      page: accountPage.page,
    })
    if (currentVersion !== requestVersion) return
    const data = response.data || {}
    accountPage.items = Array.isArray(data.items) ? data.items : []
    accountPage.page = data.page || accountPage.page
    accountPage.size = data.size || 20
    accountPage.total = Number(data.total || 0)
    accountPage.totalPages = Number(data.totalPages || 0)

    if (accountPage.totalPages > 0 && accountPage.page > accountPage.totalPages) {
      accountPage.page = accountPage.totalPages
      await loadAccounts()
    }
  } catch (error) {
    await handleSecurityError(error)
    authStore.showNotice(error.message || '账号列表加载失败')
  } finally {
    if (currentVersion === requestVersion) loading.value = false
  }
}

/**
 * 搜索输入停止 320ms 后从第 1 页请求服务端，筛选和搜索不会在前端扫描完整账号列表。
 */
function scheduleSearch() {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(applyFilters, 320)
}

function applyFilters() {
  if (searchTimer) {
    window.clearTimeout(searchTimer)
    searchTimer = null
  }
  accountPage.page = 1
  loadAccounts()
}

function changePage(page) {
  if (loading.value || page < 1 || page > accountPage.totalPages) return
  accountPage.page = page
  loadAccounts()
}

/**
 * 打开角色二次确认，目标角色始终取当前角色的另一种合法值，页面不允许提交任意字符串。
 */
function openRoleConfirmation(account) {
  pendingAction.value = {
    type: 'role',
    account,
    nextValue: account.role === 'ADMIN' ? 'USER' : 'ADMIN',
  }
}

/**
 * 打开启停二次确认；停用弹窗明确说明现有登录会话和下一次登录的行为差异。
 */
function openStatusConfirmation(account) {
  pendingAction.value = {
    type: 'status',
    account,
    nextValue: account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
  }
}

function closeConfirmation() {
  if (!submitting.value) pendingAction.value = null
}

/**
 * 携带账号 version 提交一次角色或状态修改；遇到冲突只刷新列表，不自动重放写请求。
 */
async function confirmAction() {
  const action = pendingAction.value
  if (!action || submitting.value) return
  submitting.value = true
  try {
    await authStore.ensureCsrfToken()
    if (action.type === 'role') {
      await updateAdminAccountRole(action.account.accountId, action.nextValue, action.account.version)
      authStore.showNotice('角色调整成功')
    } else {
      await updateAdminAccountStatus(action.account.accountId, action.nextValue, action.account.version)
      authStore.showNotice(action.nextValue === 'ACTIVE' ? '账号已启用' : '账号已停用，将在下次登录时生效')
    }
    console.info('[账号管理] 管理操作成功', {
      targetAccountId: action.account.accountId,
      type: action.type,
      nextValue: action.nextValue,
    })
    pendingAction.value = null
    await loadAccounts()
  } catch (error) {
    await handleSecurityError(error)
    authStore.showNotice(error.message || '账号调整失败')
    if (error.status === 404 || error.status === 409) {
      pendingAction.value = null
      await loadAccounts()
    }
  } finally {
    submitting.value = false
  }
}

/**
 * admin 身份失效时立即离开 Console；CSRF 403 只重新核对身份，不重放刚才的修改请求。
 */
async function handleSecurityError(error) {
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

function accountInitial(account) {
  return Array.from(account || 'K')[0]?.toUpperCase() || 'K'
}

function roleLabel(role) {
  return role === 'ADMIN' ? 'admin' : '普通用户'
}

function statusLabel(status) {
  return status === 'DISABLED' ? '已停用' : '正常'
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
</script>

<style scoped>
.account-management-card {
  border: 1px solid var(--border);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: var(--shadow-soft);
  padding: 26px;
}

.account-management-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.account-management-header h2 {
  font-size: 26px;
}

.account-management-header p,
.account-management-header > strong {
  margin-top: 7px;
  color: var(--text-subtle);
  font-size: 14px;
}

.account-management-header > strong {
  white-space: nowrap;
}

.account-filters {
  margin-top: 22px;
  display: grid;
  grid-template-columns: minmax(280px, 1fr) 190px 190px;
  gap: 14px;
}

.account-search {
  position: relative;
}

.account-search input {
  padding-left: 42px;
}

.search-icon {
  position: absolute;
  z-index: 1;
  left: 15px;
  top: 50%;
  width: 16px;
  height: 16px;
  transform: translateY(-55%);
  border: 2px solid #ad8c79;
  border-radius: 50%;
}

.search-icon::after {
  content: '';
  position: absolute;
  width: 7px;
  height: 2px;
  right: -6px;
  bottom: -3px;
  transform: rotate(45deg);
  background: #ad8c79;
}

.account-table-wrap {
  position: relative;
  margin-top: 22px;
  min-height: 180px;
  overflow-x: auto;
}

.account-table {
  width: 100%;
  min-width: 1000px;
  border-collapse: collapse;
}

.account-table th {
  padding: 16px 14px;
  background: #fff0e6;
  color: #75594b;
  font-size: 13px;
  text-align: left;
}

.account-table th:first-child {
  border-radius: 12px 0 0 12px;
}

.account-table th:last-child {
  border-radius: 0 12px 12px 0;
}

.account-table td {
  padding: 16px 14px;
  border-bottom: 1px solid rgba(229, 200, 180, 0.68);
  color: var(--text-subtle);
  font-size: 13px;
  vertical-align: middle;
}

.account-identity {
  min-width: 180px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-list-avatar {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--secondary);
  color: #fff;
  font-weight: 800;
}

.account-identity > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
}

.account-identity strong {
  max-width: 170px;
  overflow: hidden;
  color: var(--text-main);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-identity small {
  border-radius: 999px;
  background: #fff0e7;
  color: var(--primary-deep);
  padding: 2px 8px;
  font-size: 11px;
}

.role-badge {
  display: inline-flex;
  border-radius: 999px;
  background: #f2efeb;
  color: #6f6258;
  padding: 5px 12px;
  font-weight: 700;
}

.role-badge.is-admin {
  background: #fff0e7;
  color: var(--primary-deep);
}

.status-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-main);
  white-space: nowrap;
}

.status-label i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--mint);
}

.status-label.is-disabled i {
  background: #d8a28e;
}

.account-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.account-action-button,
.self-operation,
.account-pagination button {
  border: 1px solid var(--border);
  border-radius: 11px;
  background: #fff;
  color: var(--text-main);
  padding: 8px 12px;
  font: inherit;
  font-weight: 700;
}

.account-action-button {
  cursor: pointer;
}

.account-action-button.is-disable {
  border-color: rgba(229, 103, 67, 0.42);
  color: var(--primary-deep);
}

.account-action-button.is-enable {
  border-color: rgba(91, 160, 131, 0.45);
  background: #eff9f5;
  color: #3f876b;
}

.self-operation {
  display: inline-block;
  border-color: transparent;
  background: #f1eeea;
  color: #a89b92;
  font-weight: 600;
}

.account-table-state {
  padding: 42px;
  color: var(--text-subtle);
  text-align: center;
}

.account-pagination {
  margin-top: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  color: var(--text-subtle);
  font-size: 13px;
}

.account-pagination > div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.account-pagination button {
  min-width: 38px;
  padding: 7px 10px;
  cursor: pointer;
}

.account-pagination button:disabled,
.account-action-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.confirm-overlay {
  position: fixed;
  z-index: 180;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(75, 54, 43, 0.3);
  backdrop-filter: blur(4px);
}

.confirm-dialog {
  width: min(460px, 100%);
  border: 1px solid var(--border);
  border-radius: 24px;
  background: #fffdf9;
  box-shadow: 0 26px 80px rgba(89, 55, 37, 0.22);
  padding: 28px;
  text-align: center;
}

.confirm-mark {
  width: 48px;
  height: 48px;
  margin: 0 auto 16px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #fff0e7;
  color: var(--primary-deep);
  font-size: 24px;
  font-weight: 800;
}

.confirm-dialog p {
  margin-top: 12px;
  color: var(--text-subtle);
}

.confirm-warning {
  border-radius: 12px;
  background: #fff3ed;
  color: #9a5a43 !important;
  padding: 10px 12px;
  text-align: left;
}

.confirm-actions {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 900px) {
  .account-management-card {
    padding: 18px;
  }

  .account-filters {
    grid-template-columns: 1fr;
  }

  .account-management-header,
  .account-pagination {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
