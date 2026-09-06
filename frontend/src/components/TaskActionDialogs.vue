<script setup lang="ts">
import { reactive, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  PRIORITY_STYLE,
  TASK_PRIORITIES,
  transferTaskApi,
  updateTaskDueApi,
  updateTaskPriorityApi,
  type TaskItem,
  type TaskPriority,
} from '../api/task'
import { resolveApiError } from '../api/auth'
import type { UserOption } from '../composables/useTaskUserOptions'

/**
 * 任务快捷操作弹窗组：调整优先级 / 调整到期时间 / 转派。
 * 列表行内操作与详情抽屉共用：父组件通过 defineExpose 的 open* 方法打开，
 * 操作成功后 emit('changed')，由父组件刷新数据。
 * 权限点：prioOwn / dueOwn / transferOwn 或 transferAssigned（显隐由父组件控制）。
 */

defineProps<{ userOptions: UserOption[] }>()
const emit = defineEmits<{ changed: [] }>()

// ---------- 调整优先级（PATCH /tasks/{id}/priority） ----------
const prioVisible = shallowRef(false)
const prioSubmitting = shallowRef(false)
const prioTask = shallowRef<TaskItem | null>(null)
const prioValue = ref<TaskPriority>('P2')

function openPriority(task: TaskItem) {
  prioTask.value = task
  prioValue.value = task.priority
  prioVisible.value = true
}

async function submitPriority() {
  if (!prioTask.value) return
  prioSubmitting.value = true
  try {
    await updateTaskPriorityApi(prioTask.value.id, prioValue.value)
    prioVisible.value = false
    ElMessage.success('已调整优先级')
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    prioSubmitting.value = false
  }
}

// ---------- 调整到期时间（PATCH /tasks/{id}/due） ----------
const dueVisible = shallowRef(false)
const dueSubmitting = shallowRef(false)
const dueTask = shallowRef<TaskItem | null>(null)
const dueValue = ref<Date | null>(null)

function openDue(task: TaskItem) {
  dueTask.value = task
  dueValue.value = task.dueAt ? new Date(task.dueAt) : null
  dueVisible.value = true
}

async function submitDue() {
  if (!dueTask.value) return
  if (!dueValue.value) {
    ElMessage.warning('请选择到期时间')
    return
  }
  dueSubmitting.value = true
  try {
    await updateTaskDueApi(dueTask.value.id, dueValue.value.toISOString())
    dueVisible.value = false
    ElMessage.success('已调整到期时间')
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    dueSubmitting.value = false
  }
}

// ---------- 转派（POST /tasks/{id}/transfer，note 必填 ≤200 字） ----------
const transferVisible = shallowRef(false)
const transferSubmitting = shallowRef(false)
const transferTask = shallowRef<TaskItem | null>(null)
const transferForm = reactive<{ newAssigneeId: number | undefined; note: string }>({
  newAssigneeId: undefined,
  note: '',
})

function openTransfer(task: TaskItem) {
  transferTask.value = task
  transferForm.newAssigneeId = undefined
  transferForm.note = ''
  transferVisible.value = true
}

async function submitTransfer() {
  if (!transferTask.value) return
  if (!transferForm.newAssigneeId) {
    ElMessage.warning('请选择新处理人')
    return
  }
  if (!transferForm.note.trim()) {
    ElMessage.warning('请填写转派说明')
    return
  }
  transferSubmitting.value = true
  try {
    await transferTaskApi(transferTask.value.id, {
      newAssigneeId: transferForm.newAssigneeId,
      note: transferForm.note.trim(),
    })
    transferVisible.value = false
    ElMessage.success('已转派')
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    transferSubmitting.value = false
  }
}

defineExpose({ openPriority, openDue, openTransfer })
</script>

<template>
  <!-- 调整优先级 -->
  <el-dialog v-model="prioVisible" title="调整优先级" width="420px" align-center append-to-body>
    <p class="dialog-desc">
      任务 <span class="tf-num">{{ prioTask?.taskNo }}</span>「{{ prioTask?.title }}」
    </p>
    <el-select v-model="prioValue" class="full-width">
      <el-option v-for="p in TASK_PRIORITIES" :key="p" :label="p" :value="p">
        <span :style="PRIORITY_STYLE[p]">{{ p }}</span>
      </el-option>
    </el-select>
    <template #footer>
      <el-button @click="prioVisible = false">取消</el-button>
      <el-button type="primary" :loading="prioSubmitting" @click="submitPriority">
        调整优先级
      </el-button>
    </template>
  </el-dialog>

  <!-- 调整到期时间 -->
  <el-dialog v-model="dueVisible" title="调整到期时间" width="420px" align-center append-to-body>
    <p class="dialog-desc">
      任务 <span class="tf-num">{{ dueTask?.taskNo }}</span>「{{ dueTask?.title }}」
    </p>
    <el-date-picker
      v-model="dueValue"
      type="datetime"
      placeholder="请选择到期时间"
      class="full-width"
    />
    <template #footer>
      <el-button @click="dueVisible = false">取消</el-button>
      <el-button type="primary" :loading="dueSubmitting" @click="submitDue">
        调整到期时间
      </el-button>
    </template>
  </el-dialog>

  <!-- 转派任务 -->
  <el-dialog v-model="transferVisible" title="转派任务" width="460px" align-center append-to-body>
    <p class="dialog-desc">
      任务 <span class="tf-num">{{ transferTask?.taskNo }}</span>「{{ transferTask?.title }}」，
      当前处理人：{{ transferTask?.assigneeName }}
    </p>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="新处理人" required>
        <el-select
          v-model="transferForm.newAssigneeId"
          placeholder="请选择处理人"
          class="full-width"
          filterable
        >
          <el-option v-for="u in userOptions" :key="u.id" :label="u.name" :value="u.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="转派说明（必填，200 字以内）" required>
        <el-input
          v-model="transferForm.note"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="说明转派原因与交接事项"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="transferVisible = false">取消</el-button>
      <el-button type="primary" :loading="transferSubmitting" @click="submitTransfer">
        转派任务
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-desc {
  font-size: 13px;
  color: #5E6D82;
  margin: 0 0 16px;
}
.full-width {
  width: 100%;
}
</style>
