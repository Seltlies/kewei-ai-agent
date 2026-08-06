<template>
  <div v-if="authStore.modalOpen" class="auth-overlay" role="presentation" @mousedown.self="closeModal">
    <section class="auth-modal" role="dialog" aria-modal="true" :aria-labelledby="titleId">
      <button class="auth-close" type="button" aria-label="关闭" :disabled="submitting" @click="closeModal">×</button>

      <div class="auth-mark" aria-hidden="true"><span /></div>
      <h2 :id="titleId">{{ isRegister ? '注册 Kewei AI Agent' : '登录 Kewei AI Agent' }}</h2>
      <p class="auth-subtitle">
        {{ isRegister ? '创建账号，开启你的专属 AI 会话空间' : '欢迎回来，继续你的 AI 对话' }}
      </p>

      <form class="auth-form" novalidate @submit.prevent="submit">
        <label class="auth-field">
          <span>账号</span>
          <div class="auth-input" :class="{ 'has-error': errors.account }">
            <span class="input-icon account-icon" aria-hidden="true" />
            <input
              ref="accountInput"
              v-model="form.account"
              name="account"
              autocomplete="username"
              :placeholder="isRegister ? '请输入唯一账号' : '请输入账号'"
              :disabled="submitting"
              @blur="validateAccount(true)"
              @input="validateAccount(false)"
            />
          </div>
          <small v-if="errors.account" class="field-error">{{ errors.account }}</small>
        </label>

        <label class="auth-field">
          <span>密码</span>
          <div class="auth-input" :class="{ 'has-error': errors.password }">
            <span class="input-icon password-icon" aria-hidden="true" />
            <input
              v-model="form.password"
              name="password"
              :type="showPassword ? 'text' : 'password'"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              placeholder="请输入密码"
              :disabled="submitting"
              @blur="validatePassword(true)"
              @input="validatePassword(false)"
            />
            <button class="visibility-button" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <small v-if="errors.password" class="field-error">{{ errors.password }}</small>
        </label>

        <div v-if="isRegister" class="password-rule" :class="{ 'is-valid': passwordRulePassed }">
          <span aria-hidden="true">i</span>
          至少 6 位，必须同时包含英文字母和数字
        </div>

        <label v-if="isRegister" class="auth-field">
          <span>确认密码</span>
          <div class="auth-input" :class="{ 'has-error': errors.confirmPassword }">
            <span class="input-icon password-icon" aria-hidden="true" />
            <input
              v-model="form.confirmPassword"
              name="confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="请再次输入密码"
              :disabled="submitting"
              @blur="validateConfirmPassword(true)"
              @input="validateConfirmPassword(false)"
            />
            <button class="visibility-button" type="button" :aria-label="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'" @click="showConfirmPassword = !showConfirmPassword">
              {{ showConfirmPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <small v-if="errors.confirmPassword" class="field-error">{{ errors.confirmPassword }}</small>
        </label>

        <p v-if="errors.form" class="form-error" role="alert">{{ errors.form }}</p>
        <button class="btn auth-submit" type="submit" :disabled="submitting">
          {{ submitting ? '处理中…' : isRegister ? '注册并登录' : '登录' }}
        </button>
      </form>

      <p class="auth-switch">
        {{ isRegister ? '已有账号？' : '还没有账号？' }}
        <button type="button" :disabled="submitting" @click="switchMode">
          {{ isRegister ? '返回登录' : '立即注册' }}
        </button>
      </p>
      <p class="auth-footnote">{{ isRegister ? '公开注册账号默认为普通用户' : '登录即表示你将进入自己的专属会话空间' }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'

const emit = defineEmits(['authenticated'])
const authStore = useAuthStore()
const accountInput = ref(null)
const submitting = ref(false)
const showPassword = ref(false)
const showConfirmPassword = ref(false)
const attempted = ref(false)
const touched = reactive({ account: false, password: false, confirmPassword: false })
const form = reactive({ account: '', password: '', confirmPassword: '' })
const errors = reactive({ account: '', password: '', confirmPassword: '', form: '' })

const isRegister = computed(() => authStore.modalMode === 'register')
const titleId = computed(() => isRegister.value ? 'register-modal-title' : 'login-modal-title')
const passwordRulePassed = computed(() => /[A-Za-z]/.test(form.password) && /\d/.test(form.password) && form.password.length >= 6)

watch(
  () => authStore.modalOpen,
  async (open) => {
    if (!open) return
    errors.form = ''
    await nextTick()
    accountInput.value?.focus()
  },
)

watch(
  () => authStore.modalMode,
  () => {
    form.password = ''
    form.confirmPassword = ''
    errors.password = ''
    errors.confirmPassword = ''
    errors.account = ''
    errors.form = ''
    showPassword.value = false
    showConfirmPassword.value = false
    attempted.value = false
    touched.password = false
    touched.confirmPassword = false
  },
)

/**
 * 按正式需求校验账号长度、字符范围和首尾字符；登录仅校验必填，避免提前暴露账号规则差异。
 */
function validateAccount(markTouched) {
  if (markTouched) touched.account = true
  if (!attempted.value && !touched.account) return true
  const account = form.account.trim()
  if (!account) {
    errors.account = '请输入账号'
    return false
  }
  if (isRegister.value) {
    const length = Array.from(account).length
    const pattern = /^[\p{Script=Han}A-Za-z0-9](?:[\p{Script=Han}A-Za-z0-9_.-]*[\p{Script=Han}A-Za-z0-9])?$/u
    if (length < 3 || length > 32) {
      errors.account = '账号长度需为 3～32 个字符'
      return false
    }
    if (!pattern.test(account)) {
      errors.account = '账号仅支持中文、字母、数字、下划线、短横线和英文句点，且首尾不能为符号'
      return false
    }
  }
  errors.account = ''
  return true
}

/**
 * 注册时实时校验密码长度和字母数字组合，登录时仅校验密码必填。
 */
function validatePassword(markTouched) {
  if (markTouched) touched.password = true
  if (!attempted.value && !touched.password) return true
  if (!form.password) {
    errors.password = '请输入密码'
    return false
  }
  if (isRegister.value && !passwordRulePassed.value) {
    errors.password = '密码至少 6 位，且必须同时包含英文字母和数字'
    return false
  }
  errors.password = ''
  if (isRegister.value && (touched.confirmPassword || attempted.value)) validateConfirmPassword(false)
  return true
}

/**
 * 注册时校验确认密码必填且与密码完全一致。
 */
function validateConfirmPassword(markTouched) {
  if (!isRegister.value) return true
  if (markTouched) touched.confirmPassword = true
  if (!attempted.value && !touched.confirmPassword) return true
  if (!form.confirmPassword) {
    errors.confirmPassword = '请再次输入密码'
    return false
  }
  if (form.confirmPassword !== form.password) {
    errors.confirmPassword = '两次输入的密码不一致'
    return false
  }
  errors.confirmPassword = ''
  return true
}

/**
 * 提交前完成全量前端校验，再调用认证 Store；失败时保留账号并清空全部密码字段。
 */
async function submit() {
  if (submitting.value) return
  attempted.value = true
  errors.form = ''
  const valid = validateAccount(true) & validatePassword(true) & validateConfirmPassword(true)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      account: form.account.trim(),
      password: form.password,
      ...(isRegister.value ? { confirmPassword: form.confirmPassword } : {}),
    }
    const account = await authStore.authenticate(authStore.modalMode, payload)
    form.password = ''
    form.confirmPassword = ''
    emit('authenticated', account)
  } catch (error) {
    if (error.businessCode === 40901) {
      errors.account = error.message
    } else {
      errors.form = error.message || (isRegister.value ? '注册失败，请重试' : '登录失败，请重试')
    }
    form.password = ''
    form.confirmPassword = ''
    touched.password = false
    touched.confirmPassword = false
  } finally {
    submitting.value = false
  }
}

/**
 * 在登录与注册模式间切换，账号继续保留，密码由模式监听器统一清空。
 */
function switchMode() {
  authStore.switchModalMode(isRegister.value ? 'login' : 'register')
}

/**
 * 关闭弹窗后清空密码和表单错误，账号不写入任何持久化存储。
 */
function closeModal() {
  if (submitting.value) return
  form.password = ''
  form.confirmPassword = ''
  errors.form = ''
  errors.account = ''
  errors.password = ''
  errors.confirmPassword = ''
  attempted.value = false
  touched.account = false
  touched.password = false
  touched.confirmPassword = false
  authStore.closeAuthModal()
}
</script>

<style scoped>
.auth-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 24px;
  overflow-y: auto;
  background: rgba(104, 78, 62, 0.28);
  backdrop-filter: blur(4px);
}

.auth-modal {
  position: relative;
  width: min(680px, 100%);
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  border: 1px solid rgba(239, 187, 144, 0.72);
  border-radius: 34px;
  background: rgba(255, 253, 249, 0.98);
  box-shadow: 0 28px 80px rgba(94, 59, 40, 0.2);
  padding: 42px 78px 34px;
}

.auth-close {
  position: absolute;
  top: 28px;
  right: 30px;
  width: 46px;
  height: 46px;
  border: 0;
  border-radius: 50%;
  background: #fff3e9;
  color: var(--text-subtle);
  font-size: 30px;
  line-height: 1;
  cursor: pointer;
}

.auth-mark {
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  margin: 0 auto 6px;
  border-radius: 50%;
  background: #fff0e5;
}

.auth-mark span {
  width: 31px;
  height: 31px;
  border: 9px solid var(--primary);
  border-radius: 50%;
}

.auth-modal h2 {
  text-align: center;
  font-size: 34px;
}

.auth-subtitle {
  margin-top: 10px;
  text-align: center;
  color: var(--text-subtle);
}

.auth-form {
  margin-top: 30px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.auth-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-weight: 700;
}

.auth-input {
  display: grid;
  grid-template-columns: 34px 1fr auto;
  align-items: center;
  min-height: 66px;
  border: 1px solid #e9bd9d;
  border-radius: 16px;
  background: #fff;
  padding: 0 16px;
}

.auth-input:focus-within {
  outline: 3px solid rgba(240, 128, 98, 0.17);
  border-color: var(--primary);
}

.auth-input.has-error {
  border-color: var(--danger);
}

.auth-input input {
  min-width: 0;
  border: 0;
  outline: 0;
  padding: 12px 8px;
  font-size: 16px;
}

.input-icon {
  display: block;
  width: 22px;
  height: 22px;
  position: relative;
  color: #b78f78;
}

.account-icon::before {
  content: '';
  position: absolute;
  inset: 0 2px 6px;
  border: 2px solid currentColor;
  border-radius: 50%;
}

.account-icon::after {
  content: '';
  position: absolute;
  left: -2px;
  right: -2px;
  bottom: -1px;
  height: 10px;
  border: 2px solid currentColor;
  border-bottom: 0;
  border-radius: 16px 16px 0 0;
}

.password-icon {
  width: 20px;
  height: 22px;
  border: 2px solid currentColor;
  border-radius: 5px;
  margin-top: 5px;
}

.password-icon::before {
  content: '';
  position: absolute;
  left: 3px;
  top: -10px;
  width: 10px;
  height: 12px;
  border: 2px solid currentColor;
  border-bottom: 0;
  border-radius: 8px 8px 0 0;
}

.visibility-button {
  border: 0;
  background: transparent;
  color: var(--text-subtle);
  cursor: pointer;
  font-weight: 700;
  padding: 8px 0 8px 8px;
}

.field-error,
.form-error {
  color: var(--danger);
  font-weight: 600;
  font-size: 13px;
}

.password-rule {
  display: flex;
  gap: 10px;
  align-items: center;
  border: 1px solid #f0bc91;
  border-radius: 14px;
  background: #fff3e8;
  padding: 12px 16px;
  color: var(--text-subtle);
  font-size: 14px;
}

.password-rule span {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  border-radius: 50%;
  color: #fff;
  background: var(--primary);
  font-weight: 800;
}

.password-rule.is-valid {
  border-color: rgba(82, 149, 118, 0.35);
  background: #f2faf5;
}

.password-rule.is-valid span {
  background: var(--mint);
}

.auth-submit {
  width: 100%;
  min-height: 62px;
  margin-top: 2px;
  font-size: 19px;
}

.auth-submit:disabled,
.auth-close:disabled,
.auth-switch button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.auth-switch {
  margin-top: 24px;
  text-align: center;
  color: var(--text-subtle);
}

.auth-switch button {
  border: 0;
  background: transparent;
  color: var(--primary-deep);
  font: inherit;
  font-weight: 800;
  cursor: pointer;
}

.auth-footnote {
  margin-top: 20px;
  text-align: center;
  color: #ae8e7b;
  font-size: 13px;
}

@media (max-width: 640px) {
  .auth-overlay {
    padding: 12px;
    align-items: start;
  }

  .auth-modal {
    margin-top: 12px;
    padding: 62px 22px 28px;
    border-radius: 26px;
  }

  .auth-modal h2 {
    font-size: 27px;
  }

  .auth-mark {
    display: none;
  }
}
</style>
