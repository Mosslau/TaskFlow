import http from './index'
import type { PageResult } from './auth'

/**
 * notification-service 接口封装（PRD 4.6.2 通知中心）。
 * axios 实例已拆响应信封：成功直接返回 data；失败经 resolveApiError 还原 code/details。
 * 网关前缀：/notification/api（vite 代理 → 127.0.0.1:8000）。
 */

// ---------- 类型 ----------
/** 通知事件类型枚举（接口设计文档 6.x） */
export type NotificationEventType =
  | 'task.assigned'
  | 'task.transferred'
  | 'task.acceptance.submitted'
  | 'task.approved'
  | 'task.rejected'
  | 'task.commented'
  | 'task.due.soon'
  | 'task.overdue'

export interface NotificationItem {
  id: number
  eventType: string
  summary: string
  taskId: number | null
  taskNo: string | null
  isRead: boolean
  createdAt: string
}

export interface NotificationQuery {
  /** 可省：不传查全部 */
  isRead?: boolean
  page: number
  size: number
}

// ---------- 展示映射 ----------
/** eventType → 中文名 */
const EVENT_TYPE_NAMES: Record<string, string> = {
  'task.assigned': '任务指派',
  'task.transferred': '任务转派',
  'task.acceptance.submitted': '提交验收',
  'task.approved': '验收通过',
  'task.rejected': '验收驳回',
  'task.commented': '新评论',
  'task.due.soon': '到期提醒',
  'task.overdue': '逾期提醒',
}

export function eventTypeName(eventType?: string | null): string {
  if (!eventType) return '通知'
  return EVENT_TYPE_NAMES[eventType] ?? eventType
}

/** eventType → 图标主题色（配合 UI 规范 teal 主色系） */
const EVENT_TYPE_COLORS: Record<string, string> = {
  'task.assigned': '#0E7C86',
  'task.transferred': '#3B6FD4',
  'task.acceptance.submitted': '#C77E2B',
  'task.approved': '#2F9E6E',
  'task.rejected': '#C8493F',
  'task.commented': '#5E6D82',
  'task.due.soon': '#C77E2B',
  'task.overdue': '#C8493F',
}

export function eventTypeColor(eventType?: string | null): string {
  return EVENT_TYPE_COLORS[eventType ?? ''] ?? '#0E7C86'
}

// ---------- 接口 ----------
export function fetchNotificationsApi(params: NotificationQuery) {
  return http.get('/notification/api/v1/notifications', { params }) as Promise<
    PageResult<NotificationItem>
  >
}

export function markNotificationReadApi(id: number) {
  return http.put(`/notification/api/v1/notifications/${id}/read`) as Promise<null>
}

export function markAllNotificationsReadApi() {
  return http.put('/notification/api/v1/notifications/read-all') as Promise<{
    updatedCount: number
  }>
}

export function fetchUnreadCountApi() {
  return http.get('/notification/api/v1/notifications/unread-count') as Promise<{ count: number }>
}
