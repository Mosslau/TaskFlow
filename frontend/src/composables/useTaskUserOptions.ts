import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { lookupUsersApi, resolveApiError } from '../api/auth'

export interface UserOption {
  id: number
  name: string
}

/**
 * 人员选项（任务创建人 / 处理人筛选与表单共用）。
 * 数据源 GET /auth/api/v1/users/lookup（登录即可，无需 manageUser），
 * 仅保留在职用户作为可选项。
 */
export function useTaskUserOptions() {
  const userOptions = ref<UserOption[]>([])

  async function loadUserOptions(keyword?: string, departmentId?: number) {
    try {
      const data = await lookupUsersApi({
        keyword: keyword?.trim() || undefined,
        departmentId,
      })
      userOptions.value = data
        .filter((u) => u.status === 'active')
        .map((u) => ({ id: u.id, name: u.name }))
    } catch (error) {
      ElMessage.error(resolveApiError(error).message)
    }
  }

  return { userOptions, loadUserOptions }
}
