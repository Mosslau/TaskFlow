import http from './index'

/**
 * stats-service 接口封装（接口设计文档 5.2，接口 #47，权限点 viewStats）。
 * axios 实例已拆响应信封：成功直接返回 data；失败经 resolveApiError 还原 code/details。
 * 网关前缀：/stats/api（vite 代理 → 127.0.0.1:8000）。
 * 口径（PRD 4.3）：只计顶层任务；区间按任务创建时间；逾期取最新日快照。
 */

// ---------- 类型 ----------
export type StatsRange = 'all' | 'month' | 'week' | 'custom'

export interface StatsQuery {
  range: StatsRange
  /** range=custom 时必填，YYYY-MM-DD（起止均含当日） */
  start?: string
  end?: string
}

/** KPI 指标（PRD 4.3.2，6 项） */
export interface StatsKpi {
  /** 任务总数（区间内） */
  total: number
  /** 已完成数（含已归档） */
  done: number
  /** 未完成数（待办+进行中+待验收） */
  unfinished: number
  todo: number
  doing: number
  /** 待验收数 */
  waiting: number
  /** 已逾期（未完成且到期时间早于当前时间） */
  overdue: number
  /** P0 且未完成任务数 */
  p0: number
  /** P0 占区间任务总数百分比（%） */
  p0Percent: number
  /** 平均完成时长（小时，1 位小数） */
  avgCompleteHours: number
  /** 按时完成率（整数百分比） */
  onTimeRate: number
}

export interface StatsTrendPoint {
  /** 周期起始日；区间跨度 ≤70 天按天聚合，否则按周聚合 */
  date: string
  created: number
  completed: number
}

export interface StatusDistributionItem {
  status: string
  count: number
}

export interface PriorityDistributionItem {
  priority: string
  count: number
}

export interface AssigneeLoadItem {
  assigneeId: number
  assigneeName: string
  /** 名下未完成任务数（降序；不含 admin） */
  unfinished: number
}

export interface StatsOverview {
  /** 区间说明文案，如「本月（2026-09-01 至 2026-09-05）」 */
  rangeLabel: string
  kpi: StatsKpi
  trend: StatsTrendPoint[]
  statusDistribution: StatusDistributionItem[]
  priorityDistribution: PriorityDistributionItem[]
  assigneeLoad: AssigneeLoadItem[]
}

// ---------- 接口 ----------
export function fetchStatsOverviewApi(query: StatsQuery): Promise<StatsOverview> {
  return http.get('/stats/api/v1/overview', { params: query }) as Promise<StatsOverview>
}
