import http from './index'

/**
 * auth-user-service 接口封装（接口设计文档 v1.0 第 3 章）。
 * axios 实例已拆响应信封：成功直接返回 data；失败经 resolveApiError 还原 code/details。
 */

// ---------- 类型 ----------
export interface LoginUser {
  id: number
  name: string
  account: string
  roleKey: string
  permissions: string[]
  mustChangePassword: boolean
}

export interface LoginResult {
  token: string
  refreshToken: string
  expiresIn: number
  user: LoginUser
}

export interface UserItem {
  id: number
  name: string
  account: string
  departmentId: number
  departmentName: string
  roleId: number
  roleKey: string
  email: string
  status: 'active' | 'disabled'
  createdAt: string
}

export interface DepartmentItem {
  id: number
  name: string
  userCount: number
  createdAt?: string
}

export interface RoleItem {
  id: number
  roleKey: string
  name: string
}

export interface PermissionKeyItem {
  key: string
  name: string
  group: string
}

export interface RolesResult {
  roles: RoleItem[]
  permissionKeys: PermissionKeyItem[]
}

export interface PermMatrixRow {
  roleKey: string
  permissions: Record<string, boolean>
}

export interface AuditLogItem {
  id: number
  operatorId: number
  operatorName: string
  action: string
  changeDetail: string | null
  createdAt: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface ApiErrorInfo {
  code: number | null
  message: string
  details: Record<string, unknown> | null
}

/** 从 axios 错误 / 拦截器 Error 中还原业务错误码、文案与 details */
export function resolveApiError(error: unknown): ApiErrorInfo {
  const body = (error as { response?: { data?: unknown } })?.response?.data
  if (body && typeof body === 'object' && 'code' in body) {
    const b = body as { code: number; message?: string; details?: Record<string, unknown> | null }
    return { code: b.code, message: b.message ?? '请求失败', details: b.details ?? null }
  }
  if (error instanceof Error) {
    return { code: null, message: error.message, details: null }
  }
  return { code: null, message: '网络异常，请稍后重试', details: null }
}

// ---------- 认证 ----------
export function loginApi(body: { account: string; password: string }) {
  return http.post('/auth/api/v1/login', body) as Promise<LoginResult>
}

export function logoutApi() {
  return http.post('/auth/api/v1/logout') as Promise<null>
}

export function changePasswordApi(body: { oldPassword: string; newPassword: string }) {
  return http.put('/auth/api/v1/password', body) as Promise<null>
}

// ---------- 人员下拉（登录即可，无需 manageUser） ----------
export interface UserLookupItem {
  id: number
  name: string
  account: string
  departmentId: number
  departmentName: string
  status: 'active' | 'disabled'
}

export function lookupUsersApi(params?: { keyword?: string; departmentId?: number }) {
  return http.get('/auth/api/v1/users/lookup', { params }) as Promise<UserLookupItem[]>
}

// ---------- 用户管理 ----------
export interface UserQuery {
  keyword?: string
  departmentId?: number
  roleId?: number
  status?: string
  page: number
  size: number
}

export function fetchUsersApi(params: UserQuery) {
  return http.get('/auth/api/v1/users', { params }) as Promise<PageResult<UserItem>>
}

export function createUserApi(body: {
  name: string
  account: string
  departmentId: number
  roleId: number
  email: string
}) {
  return http.post('/auth/api/v1/users', body) as Promise<{ id: number; initialPassword: string }>
}

export function updateUserApi(id: number, body: { departmentId: number; email: string }) {
  return http.put(`/auth/api/v1/users/${id}`, body) as Promise<UserItem>
}

export function updateUserStatusApi(id: number, status: 'active' | 'disabled') {
  return http.put(`/auth/api/v1/users/${id}/status`, { status }) as Promise<{ id: number; status: string }>
}

export function updateUserRoleApi(id: number, roleId: number) {
  return http.put(`/auth/api/v1/users/${id}/role`, { roleId }) as Promise<{ id: number; roleId: number }>
}

export function resetUserPasswordApi(id: number) {
  return http.put(`/auth/api/v1/users/${id}/password-reset`) as Promise<{ newPassword: string }>
}

// ---------- 部门管理 ----------
export function fetchDepartmentsApi(keyword?: string) {
  return http.get('/auth/api/v1/departments', { params: { keyword } }) as Promise<DepartmentItem[]>
}

export function createDepartmentApi(name: string) {
  return http.post('/auth/api/v1/departments', { name }) as Promise<{ id: number; name: string }>
}

export function updateDepartmentApi(id: number, name: string) {
  return http.put(`/auth/api/v1/departments/${id}`, { name }) as Promise<{ id: number; name: string }>
}

export function deleteDepartmentApi(id: number) {
  return http.delete(`/auth/api/v1/departments/${id}`) as Promise<null>
}

// ---------- 角色与权限矩阵 ----------
export function fetchRolesApi() {
  return http.get('/auth/api/v1/roles') as Promise<RolesResult>
}

export function fetchPermMatrixApi() {
  return http.get('/auth/api/v1/permissions/matrix') as Promise<{ matrix: PermMatrixRow[] }>
}

export function updatePermMatrixApi(body: { roleKey: string; permissionKey: string; enabled: boolean }) {
  return http.put('/auth/api/v1/permissions/matrix', body) as Promise<unknown>
}

// ---------- 审计日志 ----------
export function fetchAuditLogsApi(params: { operatorId?: number; page: number; size: number }) {
  return http.get('/auth/api/v1/audit-logs', { params }) as Promise<PageResult<AuditLogItem>>
}
