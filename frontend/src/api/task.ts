import http from './index'
import type { PageResult } from './auth'

/**
 * task-service 接口封装（接口设计文档 v1.0 第 4 章），路径前缀 /task/api/v1。
 * axios 实例已拆响应信封：成功直接返回 data；失败经 resolveApiError 还原 code/details。
 */

// ---------- 领域枚举与展示映射（UI 设计规范 2.2 / 2.5） ----------
export type TaskStatus = 'new' | 'doing' | 'wait' | 'done' | 'close'
export type TaskPriority = 'P0' | 'P1' | 'P2' | 'P3'

export interface TaskStatusMeta {
  value: TaskStatus
  name: string
  /** 色点颜色（8px 圆点） */
  color: string
  /** 文字颜色 */
  textColor: string
}

/** 五状态：待办 / 进行中 / 待验收 / 已完成 / 已归档 */
export const TASK_STATUS_OPTIONS: TaskStatusMeta[] = [
  { value: 'new', name: '待办', color: '#8A97A8', textColor: '#5E6D82' },
  { value: 'doing', name: '进行中', color: '#0E7C86', textColor: '#0E7C86' },
  { value: 'wait', name: '待验收', color: '#D9822B', textColor: '#D9822B' },
  { value: 'done', name: '已完成', color: '#2F9E6E', textColor: '#2F9E6E' },
  { value: 'close', name: '已归档', color: '#C3CBD4', textColor: '#8A97A8' },
]

export const TASK_STATUS_MAP: Record<TaskStatus, TaskStatusMeta> = Object.fromEntries(
  TASK_STATUS_OPTIONS.map((o) => [o.value, o]),
) as Record<TaskStatus, TaskStatusMeta>

const FALLBACK_STATUS_META: TaskStatusMeta = {
  value: 'new',
  name: '—',
  color: '#8A97A8',
  textColor: '#5E6D82',
}

/** 未知状态兜底（防后端新增状态导致渲染崩溃） */
export function taskStatusMeta(status: string): TaskStatusMeta {
  return (TASK_STATUS_MAP as Record<string, TaskStatusMeta>)[status] ?? {
    ...FALLBACK_STATUS_META,
    name: status,
  }
}

export const TASK_PRIORITIES: TaskPriority[] = ['P0', 'P1', 'P2', 'P3']

/** 优先级标识：P0 砖红加粗、P1 琥珀、P2 主文案、P3 辅助灰（UI 设计规范 2.5） */
export const PRIORITY_STYLE: Record<TaskPriority, { color: string; fontWeight: number }> = {
  P0: { color: '#C8493F', fontWeight: 700 },
  P1: { color: '#D9822B', fontWeight: 500 },
  P2: { color: '#1F2D3D', fontWeight: 400 },
  P3: { color: '#8A97A8', fontWeight: 400 },
}

export function priorityStyleOf(priority: string): { color: string; fontWeight: number } {
  return (PRIORITY_STYLE as Record<string, { color: string; fontWeight: number }>)[priority] ?? {
    color: '#1F2D3D',
    fontWeight: 400,
  }
}

/** 任务类型（PRD 1.2，取值即中文名） */
export const TASK_TYPES = [
  '项目开发',
  '日常事务',
  '会议事项',
  '调研分析',
  '数据报表',
  '流程审批',
] as const

/** 来源渠道展示名 */
const SOURCE_NAMES: Record<string, string> = {
  web: '网页',
  Web: '网页',
  openapi: 'OpenAPI',
  OpenAPI: 'OpenAPI',
}

export function taskSourceName(source?: string | null): string {
  if (!source) return '—'
  return SOURCE_NAMES[source] ?? source
}

/** 逾期判定：未完成（非已完成/已归档）且到期时间早于当前时间（PRD 4.2.2） */
export function isTaskOverdue(task: { status: string; dueAt?: string | null }): boolean {
  if (!task.dueAt) return false
  if (task.status === 'done' || task.status === 'close') return false
  const t = new Date(task.dueAt).getTime()
  return !Number.isNaN(t) && t < Date.now()
}

// ---------- 类型 ----------
export interface TaskItem {
  id: number
  taskNo: string
  title: string
  taskType: string
  priority: TaskPriority
  status: TaskStatus
  progress: number
  creatorId: number
  creatorName: string
  assigneeId: number
  assigneeName: string
  assigneeDepartmentName?: string | null
  dueAt: string | null
  createdAt: string
  updatedAt: string
  parentId: number | null
  parentTaskNo: string | null
  /** 树形表格前端字段：是否有子任务（顶层行默认 true，展开后按实际结果隐藏箭头） */
  hasChildren?: boolean
  /** 树形表格前端字段：懒加载的子任务 */
  children?: TaskItem[]
}

export interface TaskDetail extends TaskItem {
  description?: string | null
  source?: string | null
}

export interface TimelineItem {
  id: number
  operatorName: string
  action: string
  note: string | null
  createdAt: string
}

export interface TaskDetailResult {
  task: TaskDetail
  timeline: TimelineItem[]
  /** 子任务清单（顶层任务的详情才有内容；PRD 4.1.7） */
  subtasks?: TaskItem[]
  /** 附件清单（M4，详情接口一并返回） */
  attachments?: TaskAttachment[]
}

export type TaskScope = 'all' | 'mine' | 'assigned' | 'overdue'

export interface TaskQuery {
  keyword?: string
  status?: TaskStatus
  priority?: TaskPriority
  taskType?: string
  creatorId?: number
  assigneeId?: number
  /** 处理人部门筛选 */
  assigneeDeptId?: number
  /** 查某父任务的子任务（树形表格懒加载） */
  parentId?: number
  /** 只查顶层任务（树形表格主查询） */
  topLevel?: boolean
  scope?: TaskScope
  page: number
  size: number
}

// ---------- 4.1 任务 CRUD 与列表 ----------
export function fetchTasksApi(params: TaskQuery) {
  return http.get('/task/api/v1/tasks', { params }) as Promise<PageResult<TaskItem>>
}

export interface CreateTaskBody {
  title: string
  description?: string
  taskType?: string
  priority?: TaskPriority
  assigneeId: number
  dueAt?: string
  progress?: number
  parentId?: number | null
}

export function createTaskApi(body: CreateTaskBody) {
  return http.post('/task/api/v1/tasks', body) as Promise<TaskDetail>
}

export function fetchTaskDetailApi(id: number) {
  return http.get(`/task/api/v1/tasks/${id}`) as Promise<TaskDetailResult>
}

// ---------- 4.2 状态机动作 ----------
export function acceptTaskApi(id: number) {
  return http.post(`/task/api/v1/tasks/${id}/accept`) as Promise<null>
}

export function updateProgressApi(id: number, body: { progress: number; note?: string }) {
  return http.post(`/task/api/v1/tasks/${id}/progress`, body) as Promise<null>
}

export function submitAcceptanceApi(id: number) {
  return http.post(`/task/api/v1/tasks/${id}/submit-acceptance`) as Promise<null>
}

export function approveTaskApi(id: number) {
  return http.post(`/task/api/v1/tasks/${id}/approve`) as Promise<null>
}

export function rejectTaskApi(id: number, body: { reason: string }) {
  return http.post(`/task/api/v1/tasks/${id}/reject`, body) as Promise<null>
}

export function transferTaskApi(id: number, body: { newAssigneeId: number; note: string }) {
  return http.post(`/task/api/v1/tasks/${id}/transfer`, body) as Promise<null>
}

export function updateTaskPriorityApi(id: number, priority: TaskPriority) {
  return http.patch(`/task/api/v1/tasks/${id}/priority`, { priority }) as Promise<null>
}

export function updateTaskDueApi(id: number, dueAt: string) {
  return http.patch(`/task/api/v1/tasks/${id}/due`, { dueAt }) as Promise<null>
}

export function archiveTaskApi(id: number) {
  return http.post(`/task/api/v1/tasks/${id}/archive`) as Promise<null>
}

// ---------- 4.3 评论（M4） ----------
export interface TaskComment {
  id: number
  commenterId: number
  commenterName: string
  content: string
  createdAt: string
}

/** 评论列表（创建时间正序，接口设计文档 4.3） */
export function fetchCommentsApi(taskId: number) {
  return http.get(`/task/api/v1/tasks/${taskId}/comments`) as Promise<TaskComment[]>
}

/** 发表评论（1-500 字） */
export function createCommentApi(taskId: number, content: string) {
  return http.post(`/task/api/v1/tasks/${taskId}/comments`, { content }) as Promise<TaskComment>
}

/** 删除评论（仅本人 / admin，越权 3001） */
export function deleteCommentApi(commentId: number) {
  return http.delete(`/task/api/v1/comments/${commentId}`) as Promise<null>
}

// ---------- 4.4 附件（M4） ----------
export interface TaskAttachment {
  id: number
  originalName: string
  sizeBytes: number
  uploaderId: number
  uploaderName?: string | null
  createdAt: string
}

/** 上传附件白名单与大小上限（与后端一致，前端先拦；超限后端返回 2008） */
export const ATTACHMENT_ALLOWED_EXTS = [
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md',
  'png', 'jpg', 'jpeg', 'zip', 'rar', '7z',
] as const

export const ATTACHMENT_MAX_SIZE_BYTES = 20 * 1024 * 1024 // 20MB

export const ATTACHMENT_MAX_PER_TASK = 10

export function uploadAttachmentApi(taskId: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  return http.post(`/task/api/v1/tasks/${taskId}/attachments`, form) as Promise<TaskAttachment>
}

export function deleteAttachmentApi(attachmentId: number) {
  return http.delete(`/task/api/v1/attachments/${attachmentId}`) as Promise<null>
}

/** 附件大小展示：B / KB / MB 自适应 */
export function formatFileSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// ---------- 4.5 日程（M4） ----------
export interface CalendarTaskItem {
  id: number
  taskNo: string
  title: string
  status: TaskStatus
  priority: TaskPriority
  assigneeName: string
}

export interface CalendarDay {
  /** YYYY-MM-DD */
  date: string
  tasks: CalendarTaskItem[]
}

/** 月历数据：GET /tasks/calendar?month=YYYY-MM（可见性同任务列表，后端已过滤） */
export function fetchTaskCalendarApi(month: string) {
  return http.get('/task/api/v1/tasks/calendar', { params: { month } }) as Promise<CalendarDay[]>
}

// ---------- 4.6 导入导出（M4） ----------
export interface ImportSuccessResult {
  batchId: string
  totalRows: number
  successCount: number
}

/** 导入逐行错误（code 2012 时 details 承载） */
export interface ImportRowError {
  row: number
  reason: string
}

/** 批量导入任务（multipart；失败抛 code 2012，details 为逐行错误数组） */
export function importTasksApi(file: File) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/task/api/v1/tasks/import', form) as Promise<ImportSuccessResult>
}

// ---------- 文件流下载（fetch + blob，需自带 Authorization，不走 axios 实例） ----------

/** 从 Content-Disposition 解析文件名（支持 filename*=UTF-8'' 与 filename= 两种形式） */
function parseDownloadFilename(header: string | null, fallback: string): string {
  if (!header) return fallback
  const star = header.match(/filename\*=UTF-8''([^;]+)/i)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch {
      // 解码失败退回普通形式
    }
  }
  const plain = header.match(/filename="?([^";]+)"?/i)
  if (plain) return plain[1].trim()
  return fallback
}

/** 非 2xx 时按统一信封还原业务错误文案，否则回退 HTTP 状态 */
async function assertDownloadOk(response: Response) {
  if (response.ok) return
  let message = `下载失败（HTTP ${response.status}）`
  try {
    const body = (await response.json()) as { code?: number; message?: string }
    if (body && typeof body.code === 'number' && body.message) message = body.message
  } catch {
    // 非 JSON 错误体，保留默认文案
  }
  throw new Error(message)
}

/**
 * 触发浏览器下载：fetch 携带 Bearer token 拉取文件流 → blob → a[download]。
 * 文件名优先取响应头 Content-Disposition。
 */
async function downloadFile(url: string, fallbackName: string) {
  const token = localStorage.getItem('taskflow_token')
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (response.status === 401) {
    localStorage.removeItem('taskflow_token')
    window.location.href = '/login'
    throw new Error('登录已过期，请重新登录')
  }
  await assertDownloadOk(response)
  const blob = await response.blob()
  const filename = parseDownloadFilename(
    response.headers.get('Content-Disposition'),
    fallbackName,
  )
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(objectUrl)
}

/** 导出任务 CSV（同列表筛选参数；权限点 exportData；文件名 任务导出-YYYYMMDD-HHmm.csv） */
export function exportTasksCsv(params: Omit<TaskQuery, 'page' | 'size'>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const qs = search.toString()
  return downloadFile(`/task/api/v1/tasks/export${qs ? `?${qs}` : ''}`, '任务导出.csv')
}

/** 下载导入模板 .xlsx（权限点 create） */
export function downloadImportTemplate() {
  return downloadFile('/task/api/v1/tasks/import-template', '任务导入模板.xlsx')
}
