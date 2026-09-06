<script setup lang="ts">
import { shallowRef } from 'vue'
import UserTab from './components/UserTab.vue'
import DepartmentTab from './components/DepartmentTab.vue'
import MatrixTab from './components/MatrixTab.vue'
import AuditTab from './components/AuditTab.vue'

/**
 * 权限管理页（UI 设计规范 6）：用户 / 部门 / 角色权限矩阵 / 审计日志 四个标签页。
 * 各标签页懒加载，数据请求在各自组件内完成。
 */
const activeTab = shallowRef('users')
</script>

<template>
  <div class="tf-card perm-view">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="用户" name="users" lazy>
        <UserTab />
      </el-tab-pane>
      <el-tab-pane label="部门" name="departments" lazy>
        <DepartmentTab />
      </el-tab-pane>
      <el-tab-pane label="权限矩阵" name="matrix" lazy>
        <MatrixTab />
      </el-tab-pane>
      <el-tab-pane label="审计日志" name="audit" lazy>
        <AuditTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.perm-view {
  padding-top: 8px;
}
</style>
