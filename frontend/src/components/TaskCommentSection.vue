<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createCommentApi,
  deleteCommentApi,
  fetchCommentsApi,
  type TaskComment,
} from '../api/task'
import { resolveApiError } from '../api/auth'
import { formatDateTime } from '../utils/format'
import { useUserStore } from '../stores/user'

/**
 * 任务评论区（M4）：
 * 列表创建时间正序 + 底部输入框发送（1-500 字）；本人 / admin 的评论可删（hover 行内操作）。
 * 归档任务（close，后端 2005）输入与删除禁用，只读展示。
 * 接口：GET/POST /task/api/v1/tasks/{id}/comments，DELETE /task/api/v1/comments/{id}
 */

const props = defineProps<{
  taskId: number
  /** 已归档：只读，禁止发送与删除 */
  archived: boolean
}>()

const userStore = useUserStore()

const loading = shallowRef(false)
const comments = shallowRef<TaskComment[]>([])
const input = shallowRef('')
const sending = shallowRef(false)

async function loadComments() {
  loading.value = true
  try {
    comments.value = await fetchCommentsApi(props.taskId)
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

watch(() => props.taskId, loadComments, { immediate: true })

const trimmed = computed(() => input.value.trim())
const canSend = computed(
  () => !props.archived && trimmed.value.length >= 1 && trimmed.value.length <= 500,
)

async function handleSend() {
  if (!canSend.value || sending.value) return
  sending.value = true
  try {
    const created = await createCommentApi(props.taskId, trimmed.value)
    comments.value = [...comments.value, created]
    input.value = ''
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    sending.value = false
  }
}

/** 删除权限：仅本人或 admin（后端兜底越权 3001） */
function canDelete(c: TaskComment): boolean {
  if (props.archived) return false
  const me = userStore.userInfo
  return Boolean(me) && (c.commenterId === me!.id || me!.roleKey === 'admin')
}

async function handleDelete(c: TaskComment) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确认删除这条评论？', '删除评论', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return // 取消
  }
  try {
    await deleteCommentApi(c.id)
    comments.value = comments.value.filter((item) => item.id !== c.id)
    ElMessage.success('评论已删除')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="comment-sec">
    <div v-if="comments.length" class="comment-list">
      <div v-for="c in comments" :key="c.id" class="comment-item">
        <span class="c-avatar">{{ c.commenterName?.slice(0, 1) || '?' }}</span>
        <div class="c-main">
          <div class="c-head">
            <span class="c-name">{{ c.commenterName }}</span>
            <span class="c-time tf-num">{{ formatDateTime(c.createdAt) }}</span>
            <span v-if="canDelete(c)" class="op-link c-del" @click="handleDelete(c)">删除</span>
          </div>
          <div class="c-content">{{ c.content }}</div>
        </div>
      </div>
    </div>
    <div v-else class="comment-empty">暂无评论，来说两句吧</div>

    <!-- 输入区：归档任务禁用 -->
    <div v-if="!archived" class="comment-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        maxlength="500"
        show-word-limit
        resize="none"
        placeholder="写下你的评论（1-500 字，⌘/Ctrl + Enter 发送）"
        @keydown.meta.enter.prevent="handleSend"
        @keydown.ctrl.enter.prevent="handleSend"
      />
      <el-button
        type="primary"
        :disabled="!canSend"
        :loading="sending"
        class="send-btn"
        @click="handleSend"
      >
        发送
      </el-button>
    </div>
    <div v-else class="archived-tip">任务已归档，评论区只读</div>
  </div>
</template>

<style scoped>
.comment-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 16px;
}
.comment-item {
  display: flex;
  gap: 10px;
}
.c-avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #E3F2F3;
  color: #0E7C86;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.c-main {
  flex: 1;
  min-width: 0;
}
.c-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.c-name {
  font-size: 13px;
  font-weight: 600;
  color: #1F2D3D;
}
.c-time {
  font-size: 12px;
  color: #8A97A8;
}
/* hover 行内操作（UI 设计规范） */
.c-del {
  margin-left: auto;
  font-size: 12px;
  color: #0E7C86;
  cursor: pointer;
  opacity: 0;
  transition: opacity 120ms ease-out;
}
.comment-item:hover .c-del {
  opacity: 1;
}
.c-del:hover {
  color: #C8493F;
}
.c-content {
  margin-top: 4px;
  font-size: 13px;
  color: #5E6D82;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-empty {
  font-size: 13px;
  color: #8A97A8;
  padding: 8px 0 16px;
}
.comment-input {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}
.comment-input :deep(.el-textarea) {
  flex: 1;
}
.send-btn {
  flex: none;
}
.archived-tip {
  font-size: 12px;
  color: #8A97A8;
  padding: 6px 0 2px;
}
</style>
