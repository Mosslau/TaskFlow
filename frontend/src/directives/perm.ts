import type { Directive } from 'vue'
import { useUserStore } from '../stores/user'

/**
 * v-perm 指令：当前用户无该权限点时移除元素（v-if 语义）。
 * 用法：<el-button v-perm="'manageUser'">新增用户</el-button>
 */
export const vPerm: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    if (!binding.value) return
    const userStore = useUserStore()
    if (!userStore.hasPerm(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
