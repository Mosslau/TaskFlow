<script setup lang="ts">
import { shallowRef, useTemplateRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  downloadImportTemplate,
  importTasksApi,
  type ImportRowError,
  type ImportSuccessResult,
} from '../api/task'
import { resolveApiError } from '../api/auth'

/**
 * 任务批量导入弹窗（M4）：
 * 下载 .xlsx 模板 → 选择文件 → 导入。
 * 成功：展示批次号 / 总行数 / 成功数，emit('imported') 让列表刷新；
 * 失败：code 2012 时 details 为逐行错误 [{row, reason}]，表格逐行展示。
 * 权限点 create（入口由父组件 v-perm 控制）。
 */

const emit = defineEmits<{
  /** 导入成功，父组件应刷新任务列表 */
  imported: []
}>()

const visible = shallowRef(false)
const file = shallowRef<File | null>(null)
const fileInputRef = useTemplateRef<HTMLInputElement>('fileInputRef')
const importing = shallowRef(false)
const templateDownloading = shallowRef(false)
/** 导入成功结果 */
const successResult = shallowRef<ImportSuccessResult | null>(null)
/** 逐行错误（code 2012） */
const rowErrors = shallowRef<ImportRowError[]>([])
const failMessage = shallowRef('')

function open() {
  file.value = null
  successResult.value = null
  rowErrors.value = []
  failMessage.value = ''
  visible.value = true
}

defineExpose({ open })

function triggerPick() {
  fileInputRef.value?.click()
}

function handleFileChange(event: Event) {
  const inputEl = event.target as HTMLInputElement
  const picked = inputEl.files?.[0] ?? null
  inputEl.value = ''
  if (!picked) return
  if (!/\.xlsx$/i.test(picked.name)) {
    ElMessage.warning('仅支持 .xlsx 格式文件，请使用导入模板')
    return
  }
  file.value = picked
  successResult.value = null
  rowErrors.value = []
  failMessage.value = ''
}

async function handleDownloadTemplate() {
  templateDownloading.value = true
  try {
    await downloadImportTemplate()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    templateDownloading.value = false
  }
}

async function handleImport() {
  if (!file.value || importing.value) return
  importing.value = true
  successResult.value = null
  rowErrors.value = []
  failMessage.value = ''
  try {
    const result = await importTasksApi(file.value)
    successResult.value = result
    ElMessage.success(`导入完成：共 ${result.totalRows} 行，成功 ${result.successCount} 行`)
    emit('imported')
  } catch (error) {
    const { code, message, details } = resolveApiError(error)
    if (code === 2012 && Array.isArray(details)) {
      // 逐行错误表格展示
      rowErrors.value = details as unknown as ImportRowError[]
      failMessage.value = message
    } else {
      ElMessage.error(message)
    }
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="批量导入任务"
    width="560px"
    align-center
    :close-on-click-modal="false"
  >
    <div class="import-body">
      <!-- 第一步：下载模板 -->
      <div class="step">
        <div class="step-title">1. 下载导入模板</div>
        <p class="step-desc">按模板格式填写任务（标题 / 类型 / 优先级 / 处理人 / 到期时间等），仅支持 .xlsx。</p>
        <el-button :loading="templateDownloading" @click="handleDownloadTemplate">
          下载模板（.xlsx）
        </el-button>
      </div>

      <!-- 第二步：选择文件 -->
      <div class="step">
        <div class="step-title">2. 选择填写好的文件</div>
        <div class="file-row">
          <el-button @click="triggerPick">选择文件</el-button>
          <span v-if="file" class="file-name" :title="file.name">{{ file.name }}</span>
          <span v-else class="file-placeholder">未选择文件</span>
          <input
            ref="fileInputRef"
            type="file"
            accept=".xlsx"
            class="hidden-input"
            @change="handleFileChange"
          />
        </div>
      </div>

      <!-- 导入成功结果 -->
      <el-alert
        v-if="successResult"
        type="success"
        :closable="false"
        class="result-alert"
        :title="`导入完成：批次 ${successResult.batchId}，共 ${successResult.totalRows} 行，成功 ${successResult.successCount} 行`"
      />

      <!-- 失败：逐行错误（code 2012） -->
      <template v-if="rowErrors.length">
        <el-alert
          type="error"
          :closable="false"
          class="result-alert"
          :title="`${failMessage || '导入失败'}（${rowErrors.length} 行存在错误）`"
        />
        <el-table :data="rowErrors" size="small" max-height="220" class="error-table">
          <el-table-column prop="row" label="行号" width="80" />
          <el-table-column prop="reason" label="错误原因" show-overflow-tooltip />
        </el-table>
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
        type="primary"
        :disabled="!file"
        :loading="importing"
        @click="handleImport"
      >
        开始导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.step-title {
  font-size: 13px;
  font-weight: 600;
  color: #1F2D3D;
  margin-bottom: 6px;
}
.step-desc {
  font-size: 12px;
  color: #8A97A8;
  margin: 0 0 8px;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.file-name {
  font-size: 13px;
  color: #1F2D3D;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}
.file-placeholder {
  font-size: 13px;
  color: #8A97A8;
}
.hidden-input {
  display: none;
}
.result-alert {
  margin-top: 2px;
}
.error-table {
  margin-top: 8px;
}
</style>
