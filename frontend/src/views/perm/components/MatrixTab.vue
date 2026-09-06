<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchPermMatrixApi,
  fetchRolesApi,
  resolveApiError,
  updatePermMatrixApi,
  type PermissionKeyItem,
  type RoleItem,
} from '../../../api/auth'
import { permGroupName } from '../../../utils/format'

/**
 * 权限矩阵标签页：3 角色 × 14 权限点勾选表格（行 = 权限点按分组：任务/数据/系统，列 = 角色）。
 * 勾选即改即存：PUT /auth/api/v1/permissions/matrix；
 * admin 列的 manageUser / setPerm 两个复选框禁用灰显（服务端 3009 兜底）。
 */

/** admin 角色不可关闭的权限点 */
const ADMIN_LOCKED_PERMS = ['manageUser', 'setPerm']

const loading = shallowRef(false)
const roles = ref<RoleItem[]>([])
const permissionKeys = ref<PermissionKeyItem[]>([])
/** 矩阵本地状态：roleKey -> permissionKey -> enabled */
const matrixState = ref<Record<string, Record<string, boolean>>>({})

/** 行：权限点按分组排序（任务 / 数据 / 系统，保持接口返回顺序） */
const rows = computed(() => permissionKeys.value)

/** 分组跨行合并：同组首行合并显示分组标签 */
const groupSpan = computed(() => {
  const spans: Record<string, { first: number; count: number }> = {}
  rows.value.forEach((pk, index) => {
    if (!spans[pk.group]) {
      spans[pk.group] = { first: index, count: 0 }
    }
    spans[pk.group].count += 1
  })
  return spans
})

function spanMethod({ row, columnIndex }: { row: PermissionKeyItem; columnIndex: number }) {
  if (columnIndex !== 0) return undefined
  const span = groupSpan.value[row.group]
  if (!span) return { rowspan: 1, colspan: 1 }
  if (span.first === rows.value.indexOf(row)) {
    return { rowspan: span.count, colspan: 1 }
  }
  return { rowspan: 0, colspan: 0 }
}

async function loadMatrix() {
  loading.value = true
  try {
    const [roleData, matrixData] = await Promise.all([fetchRolesApi(), fetchPermMatrixApi()])
    roles.value = roleData.roles
    permissionKeys.value = roleData.permissionKeys
    const state: Record<string, Record<string, boolean>> = {}
    for (const row of matrixData.matrix) {
      state[row.roleKey] = { ...row.permissions }
    }
    matrixState.value = state
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

onMounted(loadMatrix)

function isLockedCell(roleKey: string, permissionKey: string) {
  return roleKey === 'admin' && ADMIN_LOCKED_PERMS.includes(permissionKey)
}

function isChecked(roleKey: string, permissionKey: string) {
  return matrixState.value[roleKey]?.[permissionKey] ?? false
}

/** 勾选变更即改即存；失败回滚勾选态 */
async function handleToggle(roleKey: string, permissionKey: string, enabled: boolean) {
  if (isLockedCell(roleKey, permissionKey)) return
  const prev = !enabled
  const roleState = matrixState.value[roleKey]
  if (roleState) roleState[permissionKey] = enabled
  try {
    await updatePermMatrixApi({ roleKey, permissionKey, enabled })
    ElMessage.success('权限已更新')
  } catch (error) {
    if (roleState) roleState[permissionKey] = prev
    const { code, message } = resolveApiError(error)
    ElMessage.error(code === 3009 ? '系统管理员的「用户管理 / 权限设置」不可关闭' : message)
  }
}
</script>

<template>
  <div v-loading="loading" class="matrix-tab">
    <el-table v-col-resizable :data="rows" :span-method="spanMethod">
      <el-table-column label="分组" width="120">
        <template #default="{ row }">
          <span class="group-tag">{{ permGroupName(row.group) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="权限点" min-width="220">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <span class="perm-key">{{ row.key }}</span>
        </template>
      </el-table-column>
      <el-table-column
        v-for="role in roles"
        :key="role.roleKey"
        :label="role.name"
        width="130"
        align="center"
      >
        <template #default="{ row }">
          <el-checkbox
            :model-value="isChecked(role.roleKey, row.key)"
            :disabled="isLockedCell(role.roleKey, row.key)"
            :title="isLockedCell(role.roleKey, row.key) ? '系统管理员的该权限不可关闭' : ''"
            @change="(val: string | number | boolean) => handleToggle(role.roleKey, row.key, Boolean(val))"
          />
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-tip">暂无权限点目录</div>
      </template>
    </el-table>
    <p class="matrix-note">勾选即改即存，每次变更均写入审计日志；「系统管理员」列的用户管理 / 权限设置不可关闭。</p>
  </div>
</template>

<style scoped>
.matrix-tab {
  min-height: 200px;
}
.group-tag {
  display: inline-block;
  background: #E3F2F3;
  color: #0E7C86;
  font-size: 12px;
  border-radius: 4px;
  padding: 2px 8px;
}
.perm-key {
  color: #8A97A8;
  font-size: 12px;
  margin-left: 8px;
}
.matrix-note {
  font-size: 12px;
  color: #8A97A8;
  margin: 12px 0 0;
}
.empty-tip {
  color: #8A97A8;
  font-size: 13px;
  padding: 32px 0;
}
</style>
