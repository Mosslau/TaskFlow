<script setup lang="ts">
import { shallowRef, useTemplateRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ATTACHMENT_ALLOWED_EXTS,
  ATTACHMENT_MAX_PER_TASK,
  ATTACHMENT_MAX_SIZE_BYTES,
  deleteAttachmentApi,
  fetchTaskDetailApi,
  formatFileSize,
  uploadAttachmentApi,
  type TaskAttachment,
} from '../api/task'
import { resolveApiError } from '../api/auth'
import { formatDateTime } from '../utils/format'
import { useUserStore } from '../stores/user'

/**
 * 任务附件区（M4）：
 * 上传按钮 + 文件列表（文件名 / 大小 / 上传人 / 时间 + 下载、删除行内操作）。
 * 前端先拦：类型白名单、≤20MB、每任务 ≤10 个；超限后端兜底返回 2008。
 * 附件列表来自任务详情接口（TaskDetailResult.attachments），上传/删除后本地刷新。
 * 归档任务（close，后端 2005）禁止上传与删除。
 */

const props = defineProps<{
  taskId: number
  /** 已归档：禁止上传与删除，仍可下载 */
  archived: boolean
  /** 详情接口已带附件列表时直接传入，避免重复请求 */
  initialAttachments?: TaskAttachment[]
}>()

const userStore = useUserStore()

const loading = shallowRef(false)
const attachments = shallowRef<TaskAttachment[]>([])
const uploading = shallowRef(false)
const fileInputRef = useTemplateRef<HTMLInputElement>('fileInputRef')

watch(
  () => [props.taskId, props.initialAttachments] as const,
  async ([, initial]) => {
    if (initial) {
      attachments.value = initial
      return
    }
    // 兜底：详情未带附件时自行拉一次详情取附件
    loading.value = true
    try {
      const data = await fetchTaskDetailApi(props.taskId)
      attachments.value = data.attachments ?? []
    } catch (error) {
      ElMessage.error(resolveApiError(error).message)
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function extOf(name: string): string {
  const idx = name.lastIndexOf('.')
  return idx >= 0 ? name.slice(idx + 1).toLowerCase() : ''
}

/** 前端先拦：类型 / 大小 / 数量（与后端限制一致） */
function validateFile(file: File): string | null {
  const ext = extOf(file.name)
  if (!ext || !(ATTACHMENT_ALLOWED_EXTS as readonly string[]).includes(ext)) {
    return `不支持的文件类型 .${ext || '未知'}，仅支持：${ATTACHMENT_ALLOWED_EXTS.join(' / ')}`
  }
  if (file.size > ATTACHMENT_MAX_SIZE_BYTES) {
    return `文件大小 ${formatFileSize(file.size)} 超出 20MB 限制`
  }
  if (attachments.value.length >= ATTACHMENT_MAX_PER_TASK) {
    return `每个任务最多 ${ATTACHMENT_MAX_PER_TASK} 个附件`
  }
  return null
}

function triggerPick() {
  fileInputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const inputEl = event.target as HTMLInputElement
  const file = inputEl.files?.[0]
  inputEl.value = '' // 允许重复选择同一文件
  if (!file) return
  const invalidReason = validateFile(file)
  if (invalidReason) {
    ElMessage.warning(invalidReason)
    return
  }
  uploading.value = true
  try {
    const created = await uploadAttachmentApi(props.taskId, file)
    attachments.value = [...attachments.value, created]
    ElMessage.success('附件已上传')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    uploading.value = false
  }
}

/** 下载：fetch 带 Authorization 拉文件流 → blob → a[download] */
async function handleDownload(a: TaskAttachment) {
  try {
    const token = localStorage.getItem('taskflow_token')
    const response = await fetch(`/task/api/v1/attachments/${a.id}/download`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      let message = `下载失败（HTTP ${response.status}）`
      try {
        const body = (await response.json()) as { message?: string }
        if (body?.message) message = body.message
      } catch {
        // 非 JSON 错误体
      }
      throw new Error(message)
    }
    const blob = await response.blob()
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = a.originalName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}

/** 删除权限：上传人或 admin（后端兜底 3001） */
function canDelete(a: TaskAttachment): boolean {
  if (props.archived) return false
  const me = userStore.userInfo
  return Boolean(me) && (a.uploaderId === me!.id || me!.roleKey === 'admin')
}

async function handleDelete(a: TaskAttachment) {
  try {
    await ElMessageBox.confirm(`删除附件「${a.originalName}」？删除后不可恢复。`, '删除附件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return // 取消
  }
  try {
    await deleteAttachmentApi(a.id)
    attachments.value = attachments.value.filter((item) => item.id !== a.id)
    ElMessage.success('附件已删除')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="attachment-sec">
    <div v-if="attachments.length" class="attachment-list">
      <div v-for="a in attachments" :key="a.id" class="attachment-item">
        <span class="att-icon" aria-hidden="true">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path
              d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"
              stroke="#0E7C86" stroke-width="1.6" stroke-linejoin="round"
            />
            <path d="M14 3v5h5" stroke="#0E7C86" stroke-width="1.6" stroke-linejoin="round" />
          </svg>
        </span>
        <div class="att-main">
          <div class="att-name" :title="a.originalName">{{ a.originalName }}</div>
          <div class="att-meta">
            <span class="tf-num">{{ formatFileSize(a.sizeBytes) }}</span>
            <span v-if="a.uploaderName">{{ a.uploaderName }}</span>
            <span class="tf-num">{{ formatDateTime(a.createdAt) }}</span>
          </div>
        </div>
        <div class="att-ops">
          <span class="op-link" @click="handleDownload(a)">下载</span>
          <span v-if="canDelete(a)" class="op-link danger" @click="handleDelete(a)">删除</span>
        </div>
      </div>
    </div>
    <div v-else class="attachment-empty">暂无附件</div>

    <!-- 上传入口：归档任务禁用 -->
    <div v-if="!archived" class="upload-row">
      <el-button :loading="uploading" @click="triggerPick">上传附件</el-button>
      <span class="upload-tip">支持 {{ ATTACHMENT_ALLOWED_EXTS.join(' / ') }}，单个 ≤20MB，最多 {{ ATTACHMENT_MAX_PER_TASK }} 个</span>
      <input
        ref="fileInputRef"
        type="file"
        class="hidden-input"
        :accept="ATTACHMENT_ALLOWED_EXTS.map((e) => '.' + e).join(',')"
        @change="handleFileChange"
      />
    </div>
    <div v-else class="archived-tip">任务已归档，仅可下载已有附件</div>
  </div>
</template>

<style scoped>
.attachment-list {
  border: 1px solid #E8ECF1;
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 12px;
}
.attachment-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid #E8ECF1;
}
.attachment-item:last-child {
  border-bottom: none;
}
.attachment-item:hover {
  background: #F6F7F9;
}
.att-icon {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #E3F2F3;
  display: flex;
  align-items: center;
  justify-content: center;
}
.att-main {
  flex: 1;
  min-width: 0;
}
.att-name {
  font-size: 13px;
  color: #1F2D3D;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.att-meta {
  display: flex;
  gap: 10px;
  margin-top: 2px;
  font-size: 12px;
  color: #8A97A8;
}
/* 行内操作：hover 显现 */
.att-ops {
  flex: none;
  display: flex;
  gap: 12px;
  opacity: 0;
  transition: opacity 120ms ease-out;
}
.attachment-item:hover .att-ops {
  opacity: 1;
}
.op-link {
  font-size: 13px;
  color: #0E7C86;
  cursor: pointer;
  user-select: none;
}
.op-link:hover {
  color: #0A5F67;
}
.op-link.danger:hover {
  color: #C8493F;
}
.attachment-empty {
  font-size: 13px;
  color: #8A97A8;
  padding: 4px 0 12px;
}
.upload-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.upload-tip {
  font-size: 12px;
  color: #8A97A8;
}
.hidden-input {
  display: none;
}
.archived-tip {
  font-size: 12px;
  color: #8A97A8;
  padding: 6px 0 2px;
}
</style>
