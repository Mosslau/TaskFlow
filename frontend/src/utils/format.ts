/**
 * 展示格式化工具（纯函数，不进 composable）。
 * 时间：接口返回 ISO 8601 UTC，统一按东八区渲染（接口设计文档 1.1）。
 */

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return dateTimeFormatter.format(d).replace(/\//g, '-')
}

/** roleKey → 角色名（PRD 1.2 术语） */
const ROLE_NAMES: Record<string, string> = {
  admin: '系统管理员',
  taskAdmin: '任务管理员',
  user: '普通用户',
}

export function roleName(roleKey?: string | null): string {
  if (!roleKey) return '—'
  return ROLE_NAMES[roleKey] ?? roleKey
}

/** 权限点分组名（矩阵行分组：任务 / 数据 / 系统） */
const GROUP_NAMES: Record<string, string> = {
  task: '任务',
  data: '数据',
  system: '系统',
}

export function permGroupName(group: string): string {
  return GROUP_NAMES[group] ?? group
}

/** 审计日志变更内容：JSON 美化，非 JSON 原样展示 */
export function prettyJson(text?: string | null): string {
  if (!text) return '—'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
