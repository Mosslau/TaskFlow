import { defineStore } from 'pinia'

interface UserInfo {
  id: number
  name: string
  account: string
  roleKey: string
  mustChangePassword: boolean
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

/**
 * 当前登录用户（M1 由登录接口填充完整数据）。
 * token / userInfo / permissions 均持久化到 localStorage，刷新页面后布局与权限指令仍可用。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('taskflow_token') ?? '',
    userInfo: readJson<UserInfo | null>('taskflow_user', null),
    permissions: readJson<string[]>('taskflow_perms', []),
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
  },
  actions: {
    setLogin(token: string, userInfo: UserInfo, permissions: string[]) {
      this.token = token
      this.userInfo = userInfo
      this.permissions = permissions
      localStorage.setItem('taskflow_token', token)
      localStorage.setItem('taskflow_user', JSON.stringify(userInfo))
      localStorage.setItem('taskflow_perms', JSON.stringify(permissions))
    },
    logout() {
      this.token = ''
      this.userInfo = null
      this.permissions = []
      localStorage.removeItem('taskflow_token')
      localStorage.removeItem('taskflow_user')
      localStorage.removeItem('taskflow_perms')
    },
    hasPerm(perm: string) {
      return this.permissions.includes(perm)
    },
  },
})
