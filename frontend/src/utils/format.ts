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

/** 权限点 key → 中文名（PRD 3.2 的 14 个权限点） */
const PERM_NAMES: Record<string, string> = {
  viewAll: '查看全部任务',
  create: '创建任务',
  editOwn: '编辑自己的任务',
  deleteOwn: '删除自己的任务',
  transferOwn: '转派自己的任务',
  prioOwn: '调整自己任务优先级',
  dueOwn: '调整自己任务到期时间',
  viewAssigned: '查看指派给我的',
  updateAssigned: '更新指派给我的进度',
  transferAssigned: '转派指派给我的',
  viewStats: '查看统计总览',
  exportData: '导出数据',
  manageUser: '用户与角色管理',
  setPerm: '配置权限矩阵',
}

export function permName(key?: string | null): string {
  if (!key) return '—'
  return PERM_NAMES[key] ?? key
}

/** 审计日志操作类型 → 中文（后端存英文动作键，展示层映射） */
const AUDIT_ACTION_NAMES: Record<string, string> = {
  'permission.matrix.update': '权限矩阵变更',
}

export function auditActionName(action?: string | null): string {
  if (!action) return '—'
  return AUDIT_ACTION_NAMES[action] ?? action
}

/**
 * 审计变更内容 → 可读中文文案。
 * 矩阵变更结构 { "viewAll": { "admin": [false, true] } }
 *   → "查看全部任务：系统管理员 关闭 → 开启"
 * 解析失败或非矩阵结构时回退 JSON 美化文本。
 */
export function auditDetailText(changeDetail?: string | null): string {
  if (!changeDetail) return '—'
  try {
    const detail = JSON.parse(changeDetail) as Record<string, Record<string, [boolean, boolean]>>
    const lines: string[] = []
    for (const [permKey, roleChanges] of Object.entries(detail)) {
      for (const [roleKey, [before, after]] of Object.entries(roleChanges)) {
        lines.push(
          `${permName(permKey)}：${roleName(roleKey)} ${before ? '开启' : '关闭'} → ${after ? '开启' : '关闭'}`,
        )
      }
    }
    return lines.length > 0 ? lines.join('；') : prettyJson(changeDetail)
  } catch {
    return changeDetail
  }
}
