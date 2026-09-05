<script setup lang="ts">
import { useUserStore } from '../../stores/user'
import { useRouter } from 'vue-router'

const userStore = useUserStore()
const router = useRouter()

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="home-placeholder">
    <h1>TaskFlow 首页骨架</h1>
    <p>M0 脚手架占位页。任务列表 / 统计总览 / 日程 / 权限管理将在后续里程碑实现。</p>
    <el-button @click="handleLogout">退出登录</el-button>
  </div>
</template>

<style scoped>
.home-placeholder {
  padding: 48px;
}
h1 {
  font-size: 20px;
  color: #12242E;
}
p {
  color: #5E6D82;
  margin: 12px 0 24px;
}
</style>
