import { defineStore } from 'pinia'

interface UserInfo {
  id: number
  name: string
  account: string
  roleKey: string
  mustChangePassword: boolean
}

/**
 * 当前登录用户（M1 由登录接口填充完整数据）。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('taskflow_token') ?? '',
    userInfo: null as UserInfo | null,
    permissions: [] as string[],
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
    },
    logout() {
      this.token = ''
      this.userInfo = null
      this.permissions = []
      localStorage.removeItem('taskflow_token')
    },
    hasPerm(perm: string) {
      return this.permissions.includes(perm)
    },
  },
})
