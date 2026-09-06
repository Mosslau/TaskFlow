import { ref, shallowRef } from 'vue'
import { fetchUsersApi } from '../api/auth'
import { useUserStore } from '../stores/user'

export interface UserOption {
  id: number
  name: string
}

/**
 * 人员选项（任务创建人 / 处理人筛选与表单共用）。
 *
 * 数据源 GET /auth/api/v1/users?page=1&size=50 需要 manageUser 权限；
 * 无权限时静默降级：选项只保留「我」（当前登录用户），筛选等价于 creatorId/assigneeId = 自己。
 */
export function useTaskUserOptions() {
  const userStore = useUserStore()
  const userOptions = ref<UserOption[]>([])
  /** true 表示接口不可用（无 manageUser 权限等），已降级为「仅我」 */
  const degraded = shallowRef(false)

  async function loadUserOptions() {
    try {
      const data = await fetchUsersApi({ page: 1, size: 50 })
      userOptions.value = data.list
        .filter((u) => u.status === 'active')
        .map((u) => ({ id: u.id, name: u.name }))
      degraded.value = false
    } catch {
      // 无权限 / 接口异常：静默降级为「仅我」，不打扰用户
      degraded.value = true
      const me = userStore.userInfo
      userOptions.value = me ? [{ id: me.id, name: `${me.name}（我）` }] : []
    }
  }

  return { userOptions, degraded, loadUserOptions }
}
