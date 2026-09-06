<script setup lang="ts">
import { reactive, shallowRef, watch } from 'vue'
import UserTab from './components/UserTab.vue'
import DepartmentTab from './components/DepartmentTab.vue'
import MatrixTab from './components/MatrixTab.vue'
import AuditTab from './components/AuditTab.vue'

/**
 * 权限管理页（UI 设计规范 6）：用户 / 部门 / 角色权限矩阵 / 审计日志 四个标签页。
 * 各标签页懒加载；切换标签时递增 visit 计数迫使子组件重挂载，保证展示的是最新数据
 * （修复：矩阵调整后审计日志需手动刷新才显示的问题）。
 */
const activeTab = shallowRef('users')

/** 各标签页的激活次数，作为子组件 :key 的一部分 */
const visits = reactive<Record<string, number>>({
  users: 0,
  departments: 0,
  matrix: 0,
  audit: 0,
})

watch(activeTab, (tab) => {
  visits[tab]++
})
</script>

<template>
  <div class="tf-card perm-view">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="用户" name="users" lazy>
        <UserTab :key="`users-${visits.users}`" />
      </el-tab-pane>
      <el-tab-pane label="部门" name="departments" lazy>
        <DepartmentTab :key="`departments-${visits.departments}`" />
      </el-tab-pane>
      <el-tab-pane label="权限矩阵" name="matrix" lazy>
        <MatrixTab :key="`matrix-${visits.matrix}`" />
      </el-tab-pane>
      <el-tab-pane label="审计日志" name="audit" lazy>
        <AuditTab :key="`audit-${visits.audit}`" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.perm-view {
  padding-top: 8px;
}
</style>
