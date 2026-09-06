<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchAuditLogsApi,
  fetchUsersApi,
  resolveApiError,
  type AuditLogItem,
  type UserItem,
} from '../../../api/auth'
import { formatDateTime, prettyJson } from '../../../utils/format'

/**
 * 审计日志标签页（仅 admin）：时间倒序，可按操作人筛选（下拉来自用户列表）。
 * 变更内容 changeDetail 按 JSON 美化展示。
 */
const loading = shallowRef(false)
const logs = ref<AuditLogItem[]>([])
const total = shallowRef(0)
const page = shallowRef(1)
const size = shallowRef(20)
const operatorId = shallowRef<number | undefined>(undefined)
const operators = ref<UserItem[]>([])

async function loadLogs() {
  loading.value = true
  try {
    const data = await fetchAuditLogsApi({
      operatorId: operatorId.value,
      page: page.value,
      size: size.value,
    })
    logs.value = data.list
    total.value = data.total
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

function handleFilter() {
  page.value = 1
  loadLogs()
}

onMounted(async () => {
  loadLogs()
  try {
    // 操作人下拉：取用户列表（最多 50 条）
    const data = await fetchUsersApi({ page: 1, size: 50 })
    operators.value = data.list
  } catch {
    // 下拉加载失败不阻塞日志列表
  }
})
</script>

<template>
  <div class="audit-tab">
    <div class="toolbar">
      <el-select
        v-model="operatorId"
        placeholder="按操作人筛选"
        clearable
        class="operator-select"
        @change="handleFilter"
      >
        <el-option v-for="u in operators" :key="u.id" :label="u.name" :value="u.id" />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="logs">
      <el-table-column label="时间" width="160">
        <template #default="{ row }">
          <span class="tf-num">{{ formatDateTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="operatorName" label="操作人" width="120" />
      <el-table-column prop="action" label="操作类型" width="160" />
      <el-table-column label="变更内容" min-width="320">
        <template #default="{ row }">
          <pre class="detail-pre tf-num">{{ prettyJson(row.changeDetail) }}</pre>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-tip">暂无审计日志</div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="loadLogs"
        @size-change="handleFilter"
      />
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  margin-bottom: 16px;
}
.operator-select {
  width: 200px;
}
.detail-pre {
  margin: 0;
  font-family: inherit;
  font-size: 12px;
  color: #5E6D82;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.empty-tip {
  color: #8A97A8;
  font-size: 13px;
  padding: 32px 0;
}
</style>
