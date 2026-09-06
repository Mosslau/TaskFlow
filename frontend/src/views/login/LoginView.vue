<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { loginApi, resolveApiError } from '../../api/auth'
import ForceChangePasswordDialog from './components/ForceChangePasswordDialog.vue'

/**
 * 登录页：左右分栏布局（docs/ui/登录页.html 的 Vue 还原）。
 * M1 接入 POST /auth/api/v1/login；mustChangePassword=true 时弹强制改密对话框。
 */

const router = useRouter()
const userStore = useUserStore()

const form = reactive({
  account: '',
  password: '',
})
const errorMessage = shallowRef('')
const submitting = shallowRef(false)

const pwdDialogVisible = shallowRef(false)

/** 登录失败按错误码渲染内联错误条（接口设计文档第 6 章） */
function resolveLoginError(error: unknown): string {
  const { code, message, details } = resolveApiError(error)
  switch (code) {
    case 3002: {
      const remaining = details?.remainingAttempts
      return typeof remaining === 'number'
        ? `账号或密码错误，还可尝试 ${remaining} 次`
        : '账号或密码错误'
    }
    case 3003:
      return '账号锁定中，请 15 分钟后重试'
    case 3004:
      return '账号已停用，请联系系统管理员'
    default:
      return message || '登录失败，请稍后重试'
  }
}

async function handleLogin() {
  errorMessage.value = ''
  if (!form.account.trim()) {
    errorMessage.value = '请输入账号'
    return
  }
  if (!form.password) {
    errorMessage.value = '请输入密码'
    return
  }
  submitting.value = true
  try {
    const data = await loginApi({ account: form.account.trim(), password: form.password })
    const { permissions, ...userInfo } = data.user
    userStore.setLogin(data.token, userInfo, permissions)
    if (data.user.mustChangePassword) {
      // 强制改密：留在登录页弹对话框，改密成功后需重新登录
      pwdDialogVisible.value = true
    } else {
      router.push('/')
    }
  } catch (error) {
    errorMessage.value = resolveLoginError(error)
  } finally {
    submitting.value = false
  }
}

/** 改密成功：旧令牌已全部失效，清空登录态回到登录页 */
function handlePasswordChanged() {
  userStore.logout()
  form.password = ''
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <aside class="brand-side">
      <div class="brand-inner">
        <div class="brand-mark">
          <svg width="48" height="48" viewBox="0 0 48 48" aria-hidden="true">
            <rect width="48" height="48" rx="10" fill="#0E7C86" />
            <polyline points="12,30 22,18 34,26" fill="none" stroke="#FFFFFF" stroke-width="2.5"
                      stroke-linecap="round" stroke-linejoin="round" />
            <circle cx="12" cy="30" r="3.4" fill="#FFFFFF" />
            <circle cx="22" cy="18" r="3.4" fill="none" stroke="#FFFFFF" stroke-width="2" />
            <circle cx="34" cy="26" r="3.4" fill="#FFFFFF" />
            <circle cx="34" cy="26" r="1.4" fill="#0E7C86" />
          </svg>
          <div>
            <div class="brand-name">TaskFlow</div>
            <div class="brand-sub">企业任务管理系统</div>
          </div>
        </div>
        <p class="brand-slogan">任务分派、进度跟踪、逾期提醒，一处搞定</p>
        <ul class="brand-features">
          <li>任务全生命周期在线化</li>
          <li>逾期自动提醒</li>
          <li>权限按角色可控</li>
        </ul>
      </div>
    </aside>

    <!-- 右侧登录区 -->
    <main class="form-side">
      <div class="login-card">
        <h1 class="card-title">登录</h1>
        <p class="card-sub">使用企业账号登录系统</p>

        <el-form label-position="top" @submit.prevent="handleLogin">
          <el-form-item label="账号">
            <el-input v-model="form.account" placeholder="字母 / 数字 / 下划线" size="large" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" placeholder="请输入密码"
                      size="large" show-password @keyup.enter="handleLogin" />
          </el-form-item>

          <div v-if="errorMessage" class="error-banner" role="alert">{{ errorMessage }}</div>

          <el-button type="primary" size="large" class="login-btn" native-type="submit"
                     :loading="submitting">
            登 录
          </el-button>
        </el-form>

        <p class="card-note">首次登录将要求修改初始密码 · 连续失败 5 次锁定 15 分钟</p>
      </div>
      <p class="page-footer">TaskFlow v1.0 · 仅限企业内部使用</p>
    </main>

    <ForceChangePasswordDialog
      v-model:visible="pwdDialogVisible"
      :old-password="form.password"
      @success="handlePasswordChanged"
    />
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  min-height: 100vh;
  min-width: 1280px;
}

/* 左侧品牌区 */
.brand-side {
  flex: 0 0 55%;
  background: #12242E;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-inner {
  max-width: 420px;
  padding: 0 48px;
}
.brand-mark {
  display: flex;
  align-items: center;
  gap: 14px;
}
.brand-name {
  color: #FFFFFF;
  font-size: 28px;
  font-weight: 600;
}
.brand-sub {
  color: rgba(255, 255, 255, 0.6);
  font-size: 14px;
  margin-top: 4px;
}
.brand-slogan {
  color: rgba(255, 255, 255, 0.75);
  font-size: 16px;
  margin: 32px 0 0;
}
.brand-features {
  margin: 48px 0 0;
  padding: 0;
  list-style: none;
}
.brand-features li {
  color: rgba(255, 255, 255, 0.6);
  font-size: 12px;
  line-height: 2.2;
  padding-left: 16px;
  position: relative;
}
.brand-features li::before {
  content: "";
  position: absolute;
  left: 0;
  top: 10px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #0E7C86;
}

/* 右侧登录区 */
.form-side {
  flex: 1;
  background: #F6F7F9;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 400px;
  background: #FFFFFF;
  border: 1px solid #D8DEE6;
  border-radius: 8px;
  padding: 32px;
}
.card-title {
  font-size: 20px;
  font-weight: 600;
  color: #12242E;
  margin: 0;
}
.card-sub {
  font-size: 12px;
  color: #8A97A8;
  margin: 4px 0 24px;
}
.error-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(200, 73, 63, 0.08);
  color: #C8493F;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 16px;
}
.login-btn {
  width: 100%;
  height: 40px;
  font-size: 15px;
  letter-spacing: 4px;
  text-indent: 4px;
}
.card-note {
  font-size: 12px;
  color: #8A97A8;
  text-align: center;
  margin: 16px 0 0;
}
.page-footer {
  font-size: 12px;
  color: #8A97A8;
  margin-top: 24px;
}
</style>
