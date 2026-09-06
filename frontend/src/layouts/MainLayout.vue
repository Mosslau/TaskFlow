<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { changePasswordApi, logoutApi } from '../api/auth'
import { roleName } from '../utils/format'
import { resolveApiError } from '../api/auth'

/**
 * 全局布局框架（UI 设计规范 4.2 页面骨架）：
 * 左侧墨青侧边栏 232px（品牌区 + 导航 + 底部用户卡）+ 顶栏 56px + 纸灰内容区。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

interface NavItem {
  key: string
  title: string
  path: string
  /** 需要的权限点；空串表示登录即可见 */
  perm: string
  /** M1 是否已接入路由 */
  ready: boolean
}

const navItems: NavItem[] = [
  { key: 'tasks', title: '任务列表', path: '/tasks', perm: '', ready: false },
  { key: 'stats', title: '统计总览', path: '/stats', perm: 'viewStats', ready: false },
  { key: 'calendar', title: '日程', path: '/calendar', perm: '', ready: false },
  { key: 'notice', title: '通知中心', path: '/notifications', perm: '', ready: false },
  { key: 'perm', title: '权限管理', path: '/perm', perm: 'manageUser', ready: true },
]

const pageTitle = computed(() => (route.meta.title as string) ?? '')

const userName = computed(() => userStore.userInfo?.name ?? '')
const userRoleName = computed(() => roleName(userStore.userInfo?.roleKey))
const avatarChar = computed(() => userName.value.slice(0, 1) || '?')

/** 用户下拉菜单命令分发 */
function handleUserCommand(command: string) {
  if (command === 'logout') {
    handleLogout()
  } else if (command === 'changePassword') {
    openChangePassword()
  }
}

async function handleLogout() {
  try {
    await logoutApi()
  } catch {
    // 登出接口失败不阻塞本地清理
  }
  userStore.logout()
  router.push('/login')
}

// ---------- 修改密码（所有登录用户含 admin，接口 #4） ----------
const pwdDialogVisible = ref(false)
const pwdSubmitting = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

function openChangePassword() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdDialogVisible.value = true
}

async function submitChangePassword() {
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }
  pwdSubmitting.value = true
  try {
    await changePasswordApi({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    pwdDialogVisible.value = false
    ElMessage.success('密码已修改，请重新登录')
    userStore.logout()
    router.push('/login')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    pwdSubmitting.value = false
  }
}
</script>

<template>
  <div class="main-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="brand">
        <svg width="36" height="36" viewBox="0 0 48 48" aria-hidden="true">
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

      <nav class="nav">
        <template v-for="item in navItems" :key="item.key">
          <router-link
            v-if="item.ready"
            v-perm="item.perm"
            :to="item.path"
            class="nav-item"
            :class="{ 'is-active': route.path === item.path }"
          >
            {{ item.title }}
          </router-link>
          <el-tooltip v-else-if="!item.perm || userStore.hasPerm(item.perm)" content="后续里程碑开放" placement="right">
            <span class="nav-item is-disabled">{{ item.title }}</span>
          </el-tooltip>
        </template>
      </nav>

      <div class="user-card">
        <span class="avatar">{{ avatarChar }}</span>
        <div class="user-meta">
          <div class="user-name">{{ userName }}</div>
          <div class="user-role">{{ userRoleName }}</div>
        </div>
      </div>
    </aside>

    <!-- 主区 -->
    <div class="main-area">
      <header class="topbar">
        <h1 class="page-title">{{ pageTitle }}</h1>
        <div class="topbar-right">
          <el-tooltip content="通知中心（后续里程碑开放）" placement="bottom">
            <button class="bell-btn" type="button" aria-label="通知">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" stroke="#5E6D82"
                      stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M13.7 21a2 2 0 0 1-3.4 0" stroke="#5E6D82" stroke-width="1.8"
                      stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </el-tooltip>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-dropdown">
              {{ userName }}
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="m6 9 6 6 6-6" stroke="#5E6D82" stroke-width="2"
                      stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

    <!-- 修改密码对话框 -->
    <el-dialog v-model="pwdDialogVisible" title="修改密码" width="420px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="submitChangePassword">
        <el-form-item label="旧密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password
                    placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码（8-64 位，须含字母与数字）">
          <el-input v-model="pwdForm.newPassword" type="password" show-password
                    placeholder="请输入新密码" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password
                    placeholder="请再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSubmitting" @click="submitChangePassword">
          确认修改
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.main-layout {
  display: flex;
  min-height: 100vh;
  min-width: 1280px;
}

/* 侧边栏：墨青全高 232px */
.sidebar {
  flex: 0 0 232px;
  background: #12242E;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px 24px;
}
.brand-name {
  color: #FFFFFF;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.3;
}
.brand-sub {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  margin-top: 2px;
}

.nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 12px;
}
.nav-item {
  display: block;
  height: 44px;
  line-height: 44px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 14px;
  color: #8A97A8;
  text-decoration: none;
  cursor: pointer;
  position: relative;
  transition: color 120ms ease-out, background-color 120ms ease-out;
}
.nav-item:not(.is-disabled):hover {
  color: #FFFFFF;
}
.nav-item.is-active {
  color: #FFFFFF;
  background: rgba(14, 124, 134, 0.15);
}
.nav-item.is-active::before {
  content: "";
  position: absolute;
  left: -12px;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #0E7C86;
  border-radius: 0 2px 2px 0;
}
.nav-item.is-disabled {
  cursor: not-allowed;
  color: rgba(138, 151, 168, 0.55);
}

/* 底部用户卡 */
.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #0E7C86;
  color: #FFFFFF;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  color: #FFFFFF;
  font-size: 14px;
  line-height: 1.4;
}
.user-role {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
}

/* 顶栏 56px */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.topbar {
  flex: 0 0 56px;
  background: #FFFFFF;
  border-bottom: 1px solid #D8DEE6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #12242E;
  margin: 0;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 20px;
}
.bell-btn {
  border: none;
  background: none;
  padding: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
}
.user-dropdown {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #1F2D3D;
  cursor: pointer;
  outline: none;
}

/* 内容区：纸灰底 24px 内边距 */
.content {
  flex: 1;
  background: #F6F7F9;
  padding: 24px;
}
</style>
