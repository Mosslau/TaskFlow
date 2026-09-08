<script setup lang="ts">
import { onMounted, onUnmounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  eventTypeColor,
  eventTypeName,
  fetchNotificationsApi,
  fetchUnreadCountApi,
  markAllNotificationsReadApi,
  markNotificationReadApi,
  type NotificationItem,
} from '../api/notification'
import { resolveApiError } from '../api/auth'
import { formatRelativeTime } from '../utils/format'

/**
 * 顶栏通知中心入口（PRD 4.6.2）：
 * 铃铛 + el-badge 未读角标（60s 轮询 unread-count，挂载即查，卸载停轮询）；
 * 点击铃铛弹出消息面板（el-popover）：时间倒序列表，未读加粗带圆点，
 * 点击消息乐观标记已读并跳转任务详情（/tasks?taskId=N，由任务列表页打开详情抽屉），
 * 面板内置「全部已读」；登录即可见，无权限点控制。
 */

const router = useRouter()

const POLL_INTERVAL = 60_000

// ---------- 未读角标轮询 ----------
const unreadCount = shallowRef(0)
let pollTimer: number | undefined

async function refreshUnreadCount() {
  try {
    const data = await fetchUnreadCountApi()
    unreadCount.value = data.count
  } catch {
    // 轮询失败静默（不打扰用户），下轮重试
  }
}

onMounted(() => {
  refreshUnreadCount()
  pollTimer = window.setInterval(refreshUnreadCount, POLL_INTERVAL)
})

onUnmounted(() => {
  window.clearInterval(pollTimer)
})

// ---------- 消息面板 ----------
const panelVisible = shallowRef(false)
const loading = shallowRef(false)
const list = shallowRef<NotificationItem[]>([])
const total = shallowRef(0)
const readAllSubmitting = shallowRef(false)

async function loadList() {
  loading.value = true
  try {
    const data = await fetchNotificationsApi({ page: 1, size: 20 })
    list.value = data.list
    total.value = data.total
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

/** 铃铛点击打开面板：刷新列表与角标 */
function handlePanelShow() {
  loadList()
  refreshUnreadCount()
}

/** 点击消息项：乐观标记已读（角标同步 -1），跳转对应任务详情 */
async function handleItemClick(item: NotificationItem) {
  if (!item.isRead) {
    item.isRead = true
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    markNotificationReadApi(item.id).catch((error) => {
      // 回滚乐观更新
      item.isRead = false
      unreadCount.value += 1
      ElMessage.error(resolveApiError(error).message)
    })
  }
  panelVisible.value = false
  if (item.taskId) {
    router.push({ path: '/tasks', query: { taskId: item.taskId } })
  }
}

/** 全部已读：成功后刷新列表与角标 */
async function handleReadAll() {
  readAllSubmitting.value = true
  try {
    await markAllNotificationsReadApi()
    unreadCount.value = 0
    list.value = list.value.map((n) => ({ ...n, isRead: true }))
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    readAllSubmitting.value = false
  }
}
</script>

<template>
  <el-popover
    v-model:visible="panelVisible"
    placement="bottom-end"
    :width="380"
    trigger="click"
    popper-class="notification-popper"
    @show="handlePanelShow"
  >
    <template #reference>
      <el-badge
        :value="unreadCount"
        :max="99"
        :hidden="unreadCount === 0"
        class="bell-badge"
      >
        <button class="bell-btn" type="button" aria-label="通知中心">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" stroke="#5E6D82"
                  stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M13.7 21a2 2 0 0 1-3.4 0" stroke="#5E6D82" stroke-width="1.8"
                  stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </el-badge>
    </template>

    <!-- 消息面板 -->
    <div class="n-panel">
      <div class="n-head">
        <span class="n-title">通知中心</span>
        <el-button
          link
          type="primary"
          size="small"
          :disabled="unreadCount === 0"
          :loading="readAllSubmitting"
          @click="handleReadAll"
        >
          全部已读
        </el-button>
      </div>

      <div v-loading="loading" class="n-body">
        <!-- 空态 -->
        <div v-if="!loading && list.length === 0" class="n-empty">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" stroke="#C7D0DB"
                  stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M13.7 21a2 2 0 0 1-3.4 0" stroke="#C7D0DB" stroke-width="1.6"
                  stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <p>暂无通知消息</p>
        </div>

        <!-- 消息列表（时间倒序，后端已排序） -->
        <div
          v-for="item in list"
          :key="item.id"
          class="n-item"
          :class="{ unread: !item.isRead, clickable: !!item.taskId }"
          @click="handleItemClick(item)"
        >
          <span class="n-icon" :style="{ backgroundColor: eventTypeColor(item.eventType) + '1A' }">
            <!-- 指派 / 转派：人形 -->
            <svg v-if="item.eventType === 'task.assigned' || item.eventType === 'task.transferred'"
                 width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8" r="3.5" :stroke="eventTypeColor(item.eventType)" stroke-width="1.7" />
              <path d="M5.5 19c.8-3.2 3.4-5 6.5-5s5.7 1.8 6.5 5" :stroke="eventTypeColor(item.eventType)"
                    stroke-width="1.7" stroke-linecap="round" />
            </svg>
            <!-- 验收通过：对勾 -->
            <svg v-else-if="item.eventType === 'task.approved'" width="16" height="16"
                 viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="m5 12.5 4.5 4.5L19 7.5" :stroke="eventTypeColor(item.eventType)"
                    stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <!-- 验收驳回：叉 -->
            <svg v-else-if="item.eventType === 'task.rejected'" width="16" height="16"
                 viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M7 7l10 10M17 7L7 17" :stroke="eventTypeColor(item.eventType)"
                    stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <!-- 到期 / 逾期：时钟 -->
            <svg v-else-if="item.eventType === 'task.due.soon' || item.eventType === 'task.overdue'"
                 width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="8" :stroke="eventTypeColor(item.eventType)" stroke-width="1.7" />
              <path d="M12 8v4.5l3 1.8" :stroke="eventTypeColor(item.eventType)"
                    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <!-- 提交验收 / 新评论 / 其他：气泡 -->
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H9l-5 4V6.5Z"
                    :stroke="eventTypeColor(item.eventType)" stroke-width="1.7"
                    stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>

          <div class="n-content">
            <div class="n-summary">{{ item.summary }}</div>
            <div class="n-meta">
              <span class="n-type" :style="{ color: eventTypeColor(item.eventType) }">
                {{ eventTypeName(item.eventType) }}
              </span>
              <span v-if="item.taskNo" class="n-task-no">{{ item.taskNo }}</span>
              <span class="n-time">{{ formatRelativeTime(item.createdAt) }}</span>
            </div>
          </div>

          <span v-if="!item.isRead" class="n-dot" aria-label="未读"></span>
        </div>

        <!-- 加载态占位高度 -->
        <div v-if="loading && list.length === 0" class="n-loading-placeholder"></div>
      </div>

      <div v-if="total > list.length" class="n-foot">仅展示最近 {{ list.length }} 条，共 {{ total }} 条</div>
    </div>
  </el-popover>
</template>

<style scoped>
.bell-badge :deep(.el-badge__content) {
  border: none;
}
.bell-btn {
  border: none;
  background: none;
  padding: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
}

/* 面板 */
.n-panel {
  margin: -12px;
}
.n-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #E8ECF1;
}
.n-title {
  font-size: 14px;
  font-weight: 600;
  color: #12242E;
}
.n-body {
  max-height: 420px;
  min-height: 120px;
  overflow-y: auto;
}
.n-loading-placeholder {
  height: 120px;
}
.n-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 36px 0;
  color: #8A97A8;
  font-size: 13px;
}
.n-empty p {
  margin: 0;
}

/* 消息项 */
.n-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background-color 120ms ease-out;
}
.n-item:hover {
  background: #F6F7F9;
}
.n-item + .n-item {
  border-top: 1px solid #F0F2F5;
}
.n-item.unread .n-summary {
  font-weight: 600;
  color: #1F2D3D;
}
.n-icon {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.n-content {
  flex: 1;
  min-width: 0;
}
.n-summary {
  font-size: 13px;
  color: #5E6D82;
  line-height: 1.5;
  word-break: break-all;
}
.n-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}
.n-type {
  flex: none;
}
.n-task-no {
  color: #0E7C86;
}
.n-time {
  margin-left: auto;
  color: #8A97A8;
  flex: none;
}
.n-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #C8493F;
  margin-top: 8px;
}
.n-foot {
  padding: 8px 16px;
  border-top: 1px solid #E8ECF1;
  font-size: 12px;
  color: #8A97A8;
  text-align: center;
}
</style>

<style>
/* popper 内边距交由面板自管（非 scoped，作用于 popper 容器） */
.notification-popper.el-popover {
  padding: 0;
  border-radius: 8px;
}
</style>
