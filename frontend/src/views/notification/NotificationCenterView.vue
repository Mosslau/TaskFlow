<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  eventTypeColor,
  eventTypeName,
  fetchNotificationsApi,
  fetchUnreadCountApi,
  markAllNotificationsReadApi,
  markNotificationReadApi,
  type NotificationItem,
} from '../../api/notification'
import { resolveApiError } from '../../api/auth'
import { formatDateTime } from '../../utils/format'
import TaskDetailDrawer from '../../components/TaskDetailDrawer.vue'
import type { TaskDetail } from '../../api/task'

/**
 * 通知中心页（PRD 4.6.2，接口 #43-46）：
 * 全部 / 未读筛选 + 时间倒序分页列表；未读消息可单条"标为已读"或顶部"全部已读"；
 * 点击带任务号的消息：先乐观标已读，再在本页内就地打开任务详情抽屉（复用
 * TaskDetailDrawer，不跳转任务列表页）；
 * 加载失败给出可重试的错误态（不出现静默空白）。
 * 登录即可见，无权限点控制（与顶栏铃铛一致）。
 */
const loading = shallowRef(false)
const list = ref<NotificationItem[]>([])
const total = shallowRef(0)
const page = shallowRef(1)
const size = shallowRef(20)
const filter = shallowRef<'all' | 'unread'>('all')
const errorMsg = shallowRef('')
const readAllSubmitting = shallowRef(false)
const unreadCount = shallowRef(0)

// ---------- 任务详情抽屉（本页内就地打开，不跳转） ----------
const drawerVisible = shallowRef(false)
const drawerTaskId = shallowRef<number | null>(null)
/** 是否显示系统运维告警（mail.failed，默认隐藏；对普通业务用户本就无此类消息） */
const showSystem = shallowRef(false)

async function loadList() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await fetchNotificationsApi({
      isRead: filter.value === 'unread' ? false : undefined,
      page: page.value,
      size: size.value,
      // 默认只看业务通知；打开开关后看全部（含系统告警）
      view: showSystem.value ? 'all' : 'business',
    })
    list.value = data.list
    total.value = data.total
  } catch (error) {
    errorMsg.value = resolveApiError(error).message
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function refreshUnreadCount() {
  try {
    const data = await fetchUnreadCountApi()
    unreadCount.value = data.count
  } catch {
    // 角标刷新失败静默，列表仍可正常使用
  }
}

function handleFilterChange() {
  page.value = 1
  loadList()
}

function handlePageChange() {
  loadList()
}

/** 点击某条消息：乐观标已读（不等网络，失败回滚），带任务号则在本页就地打开任务详情抽屉 */
function handleItemClick(row: NotificationItem) {
  if (!row.isRead) {
    row.isRead = true
    if (unreadCount.value > 0) {
      unreadCount.value -= 1
    }
    markNotificationReadApi(row.id).catch((error) => {
      row.isRead = false
      unreadCount.value += 1
      ElMessage.error(resolveApiError(error).message)
    })
  }
  if (row.taskId) {
    drawerTaskId.value = row.taskId
    drawerVisible.value = true
  }
}

/** 抽屉内点击子任务：切换抽屉到子任务详情（本页内） */
function handleOpenSubtask(taskId: number) {
  drawerTaskId.value = taskId
}

/** 抽屉内"新建子任务"入口：通知页无新建弹窗，给出引导 */
function handleCreateSubtask(task: TaskDetail) {
  ElMessage.info(`请在任务列表页为「${task.taskNo}」新建子任务`)
}

/** 抽屉内任务有变更（状态机动作/字段调整）：刷新当前通知列表与角标 */
function handleDrawerChanged() {
  loadList()
  refreshUnreadCount()
}

/** 单条标为已读（不跳转） */
async function handleMarkRead(row: NotificationItem) {
  if (row.isRead) {
    return
  }
  try {
    await markNotificationReadApi(row.id)
    row.isRead = true
    if (unreadCount.value > 0) {
      unreadCount.value -= 1
    }
    if (filter.value === 'unread') {
      // 未读筛选下移除该行
      list.value = list.value.filter((n) => n.id !== row.id)
      total.value = Math.max(0, total.value - 1)
    }
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}

/** 全部已读 */
async function handleReadAll() {
  if (unreadCount.value === 0) {
    return
  }
  readAllSubmitting.value = true
  try {
    await markAllNotificationsReadApi()
    unreadCount.value = 0
    if (filter.value === 'all') {
      list.value = list.value.map((n) => ({ ...n, isRead: true }))
    } else {
      list.value = []
      total.value = 0
    }
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    readAllSubmitting.value = false
  }
}

/** 带任务号的行可点击（跳任务详情）：行级 class */
function rowClassName({ row }: { row: NotificationItem }) {
  return row.taskId ? 'notice-row-link' : ''
}

onMounted(() => {
  loadList()
  refreshUnreadCount()
})
</script>

<template>
  <div class="tf-card notice-view">
    <div class="notice-toolbar">
      <el-radio-group v-model="filter" @change="handleFilterChange">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="unread">
          未读<template v-if="unreadCount > 0">&nbsp;({{ unreadCount }})</template>
        </el-radio-button>
      </el-radio-group>

      <div class="toolbar-actions">
        <!-- 系统运维告警(mail.failed)默认不进业务通知列表,需查看时打开 -->
        <el-switch
          v-model="showSystem"
          inline-prompt
          active-text="系统告警"
          inactive-text="业务通知"
          @change="handleFilterChange"
        />
        <el-button :loading="loading" @click="loadList">刷新</el-button>
        <el-button
          type="primary"
          :disabled="unreadCount === 0"
          :loading="readAllSubmitting"
          @click="handleReadAll"
        >
          全部已读
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="errorMsg"
      :title="errorMsg"
      type="error"
      show-icon
      :closable="false"
      class="notice-error"
    >
      <el-button size="small" type="primary" link @click="loadList">重试</el-button>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="list"
      :row-class-name="rowClassName"
      @row-click="handleItemClick"
    >
      <el-table-column label="类型" width="140">
        <template #default="{ row }">
          <span class="notice-type">
            <span
              class="notice-dot"
              :style="{ backgroundColor: eventTypeColor(row.eventType) }"
            />
            {{ eventTypeName(row.eventType) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="消息内容" min-width="320">
        <template #default="{ row }">
          <span class="notice-summary" :class="{ unread: !row.isRead }">
            {{ row.summary }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="任务号" width="140">
        <template #default="{ row }">
          <span v-if="row.taskNo" class="notice-task-no">{{ row.taskNo }}</span>
          <span v-else class="notice-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">
          <span class="tf-num">{{ formatDateTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isRead ? 'info' : 'primary'" size="small" effect="plain">
            {{ row.isRead ? '已读' : '未读' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center">
        <template #default="{ row }">
          <el-button
            v-if="!row.isRead"
            link
            type="primary"
            size="small"
            @click.stop="handleMarkRead(row)"
          >
            标为已读
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="notice-empty">
          <template v-if="loading">加载中…</template>
          <template v-else-if="filter === 'unread'">暂无未读消息</template>
          <template v-else>暂无通知消息</template>
        </div>
      </template>
    </el-table>

    <div class="notice-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="handlePageChange"
        @size-change="handleFilterChange"
      />
    </div>

    <!-- 任务详情抽屉：点击消息在本页内就地打开，不跳转任务列表 -->
    <TaskDetailDrawer
      v-model="drawerVisible"
      :task-id="drawerTaskId"
      @changed="handleDrawerChanged"
      @open-subtask="handleOpenSubtask"
      @create-subtask="handleCreateSubtask"
    />
  </div>
</template>

<style scoped>
.notice-view {
  padding-top: 8px;
}
.notice-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.notice-error {
  margin-bottom: 12px;
}
.notice-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #5e6d82;
}
.notice-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.notice-summary {
  font-size: 13px;
  color: #5e6d82;
  line-height: 1.5;
  word-break: break-all;
}
.notice-summary.unread {
  color: #1f2d3d;
  font-weight: 600;
}
.notice-task-no {
  font-size: 13px;
  color: #0e7c86;
  font-family: var(--tf-font-mono, ui-monospace, monospace);
}
.notice-muted {
  color: #c0c9d4;
}
.notice-empty {
  color: #8a97a8;
  font-size: 13px;
  padding: 32px 0;
}
.notice-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
/* 带任务号的行可点击（跳任务详情），未读 hover 高亮 */
.notice-row-link {
  cursor: pointer;
}
</style>
