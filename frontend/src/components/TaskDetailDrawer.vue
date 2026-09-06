<script setup lang="ts">
import { computed, reactive, shallowRef, useTemplateRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  acceptTaskApi,
  approveTaskApi,
  archiveTaskApi,
  fetchTaskDetailApi,
  isTaskOverdue,
  priorityStyleOf,
  rejectTaskApi,
  submitAcceptanceApi,
  taskSourceName,
  taskStatusMeta,
  updateProgressApi,
  type TaskDetail,
  type TaskItem,
  type TimelineItem,
} from '../api/task'
import { resolveApiError } from '../api/auth'
import { formatDateTime } from '../utils/format'
import { useUserStore } from '../stores/user'
import { useTaskUserOptions } from '../composables/useTaskUserOptions'
import TaskActionDialogs from './TaskActionDialogs.vue'

/**
 * 任务详情抽屉（UI 设计规范 5.4，原型 docs/ui/任务详情抽屉.html）：
 * 墨青标题栏 → 操作按钮行（按状态与权限显隐）→ 字段区双列 → 描述 → 进度 → 操作时间线。
 * M2 不含子任务 / 评论 / 附件 / 邮件记录区（后续里程碑）。
 *
 * 数据：GET /task/api/v1/tasks/{id} → { task, timeline }
 * 状态机动作：accept / progress / submit-acceptance / approve / reject / transfer /
 *            PATCH priority / PATCH due / archive（接口设计文档 4.2）
 */

const props = defineProps<{ modelValue: boolean; taskId: number | null }>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 任务发生变更（状态机动作或字段调整成功），父组件应刷新列表 */
  changed: []
  /** 请求在该任务下新建子任务（由父组件打开新建弹窗，parentId 预填） */
  createSubtask: [task: TaskDetail]
  /** 请求打开某个子任务的详情（父组件切换抽屉任务） */
  openSubtask: [taskId: number]
}>()

const userStore = useUserStore()
const { userOptions, loadUserOptions } = useTaskUserOptions()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

// ---------- 详情数据 ----------
const loading = shallowRef(false)
const task = shallowRef<TaskDetail | null>(null)
const timeline = shallowRef<TimelineItem[]>([])
/** 子任务清单（顶层任务才有；PRD 4.1.7） */
const subtasks = shallowRef<TaskItem[]>([])

async function loadDetail() {
  if (!props.taskId) return
  loading.value = true
  try {
    const data = await fetchTaskDetailApi(props.taskId)
    task.value = data.task
    subtasks.value = data.subtasks ?? []
    // 时间线倒序（最新在前）
    timeline.value = [...(data.timeline ?? [])].sort(
      (a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id,
    )
  } catch (error) {
    // 2001 任务不存在或不可见等：提示并关闭抽屉
    ElMessage.error(resolveApiError(error).message)
    visible.value = false
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.taskId] as const,
  ([v, id]) => {
    if (v && id) {
      loadDetail()
      if (userOptions.value.length === 0) loadUserOptions()
    }
  },
)

// ---------- 展示派生 ----------
const statusMeta = computed(() => (task.value ? taskStatusMeta(task.value.status) : null))
const overdue = computed(() => (task.value ? isTaskOverdue(task.value) : false))
const overdueDays = computed(() => {
  if (!task.value?.dueAt || !overdue.value) return 0
  return Math.max(1, Math.ceil((Date.now() - new Date(task.value.dueAt).getTime()) / 86400000))
})

/** 最近一条「更新进度」时间线备注 */
const latestProgressNote = computed(() => {
  const item = timeline.value.find((t) => t.action?.includes('进度') && t.note)
  return item?.note ?? ''
})

/** 时间线色点：动作 → 状态色系（UI 设计规范 2.2） */
function timelineDotColor(action: string): string {
  if (action.includes('创建')) return '#8A97A8'
  if (action.includes('驳回')) return '#C8493F'
  if (action.includes('通过') || action.includes('完成')) return '#2F9E6E'
  if (action.includes('验收')) return '#D9822B'
  return '#0E7C86'
}

// ---------- 操作显隐（按任务状态 + 权限点 + 身份） ----------
const isCreator = computed(() => Boolean(task.value) && task.value!.creatorId === userStore.userInfo?.id)
const isAssignee = computed(() => Boolean(task.value) && task.value!.assigneeId === userStore.userInfo?.id)

/** 受理：待办 且 我是处理人 */
const canAccept = computed(
  () => task.value?.status === 'new' && isAssignee.value && userStore.hasPerm('updateAssigned'),
)
/** 更新进度：进行中/待办 且 我是处理人（待办提交进度自动受理） */
const canUpdateProgress = computed(
  () =>
    (task.value?.status === 'doing' || task.value?.status === 'new') &&
    isAssignee.value &&
    userStore.hasPerm('updateAssigned'),
)
/** 提交验收：进行中 且 我是处理人（有未完成子任务时后端返回 2004，直接展示 message） */
const canSubmitAcceptance = computed(
  () => task.value?.status === 'doing' && isAssignee.value && userStore.hasPerm('updateAssigned'),
)
/** 验收通过 / 驳回：待验收 且 我是创建人 */
const canApprove = computed(
  () => task.value?.status === 'wait' && isCreator.value && userStore.hasPerm('editOwn'),
)
const canReject = canApprove
/** 转派：transferOwn（我创建的）或 transferAssigned（指派给我的） */
const canTransfer = computed(() => {
  if (!task.value || task.value.status === 'done' || task.value.status === 'close') return false
  return (
    (isCreator.value && userStore.hasPerm('transferOwn')) ||
    (isAssignee.value && userStore.hasPerm('transferAssigned'))
  )
})
/** 调整优先级 / 到期：已完成、已归档任务只读（原型中对应按钮禁用，此处不展示） */
const canPriority = computed(
  () =>
    Boolean(task.value) &&
    task.value!.status !== 'done' &&
    task.value!.status !== 'close' &&
    userStore.hasPerm('prioOwn'),
)
const canDue = computed(
  () =>
    Boolean(task.value) &&
    task.value!.status !== 'done' &&
    task.value!.status !== 'close' &&
    userStore.hasPerm('dueOwn'),
)
/** 归档：已完成 且 有 viewAll 权限 */
const canArchive = computed(() => task.value?.status === 'done' && userStore.hasPerm('viewAll'))

const hasAnyAction = computed(
  () =>
    canAccept.value ||
    canUpdateProgress.value ||
    canSubmitAcceptance.value ||
    canApprove.value ||
    canTransfer.value ||
    canPriority.value ||
    canDue.value ||
    canArchive.value,
)

// ---------- 无弹窗动作 ----------
const acting = shallowRef(false)

async function runAction(fn: () => Promise<unknown>, successMsg: string) {
  if (!props.taskId) return
  acting.value = true
  try {
    await fn()
    ElMessage.success(successMsg)
    emit('changed')
    await loadDetail()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    acting.value = false
  }
}

const handleAccept = () => runAction(() => acceptTaskApi(props.taskId!), '已受理任务')
const handleSubmitAcceptance = () =>
  runAction(() => submitAcceptanceApi(props.taskId!), '已提交验收')
const handleApprove = () => runAction(() => approveTaskApi(props.taskId!), '已通过验收')
const handleArchive = () => runAction(() => archiveTaskApi(props.taskId!), '已归档')

// ---------- 更新进度弹窗（进度 0-100 步进 5 + 进展说明） ----------
const progressVisible = shallowRef(false)
const progressSubmitting = shallowRef(false)
const progressForm = reactive({ progress: 0, note: '' })

function openProgress() {
  progressForm.progress = task.value?.progress ?? 0
  progressForm.note = ''
  progressVisible.value = true
}

async function submitProgress() {
  if (!props.taskId) return
  progressSubmitting.value = true
  try {
    const note = progressForm.note.trim()
    await updateProgressApi(props.taskId, {
      progress: progressForm.progress,
      ...(note ? { note } : {}),
    })
    progressVisible.value = false
    ElMessage.success('已更新进度')
    emit('changed')
    await loadDetail()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    progressSubmitting.value = false
  }
}

// ---------- 驳回弹窗（原因必填 ≤500 字） ----------
const rejectVisible = shallowRef(false)
const rejectSubmitting = shallowRef(false)
const rejectReason = shallowRef('')

function openReject() {
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  if (!props.taskId) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  rejectSubmitting.value = true
  try {
    await rejectTaskApi(props.taskId, { reason: rejectReason.value.trim() })
    rejectVisible.value = false
    ElMessage.success('已驳回')
    emit('changed')
    await loadDetail()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    rejectSubmitting.value = false
  }
}

// ---------- 优先级 / 到期 / 转派（共用弹窗组） ----------
const actionDialogsRef = useTemplateRef<InstanceType<typeof TaskActionDialogs>>('actionDialogsRef')

const openPriority = () => task.value && actionDialogsRef.value?.openPriority(task.value)
const openDue = () => task.value && actionDialogsRef.value?.openDue(task.value)
const openTransfer = () => task.value && actionDialogsRef.value?.openTransfer(task.value)

async function handleActionChanged() {
  emit('changed')
  await loadDetail()
}
</script>

<template>
  <el-drawer v-model="visible" size="720px" :with-header="false" class="task-detail-drawer">
    <div v-loading="loading" class="drawer-body">
      <!-- 标题栏：墨青 48px，编号 + 标题 + 状态色点胶囊 -->
      <div class="d-head">
        <button class="d-close" type="button" aria-label="关闭" @click="visible = false">×</button>
        <span class="d-id tf-num">{{ task?.taskNo ?? '' }}</span>
        <span class="d-title" :title="task?.title">{{ task?.title ?? '' }}</span>
        <span v-if="statusMeta" class="d-status">
          <i :style="{ backgroundColor: statusMeta.color }"></i>{{ statusMeta.name }}
        </span>
      </div>

      <template v-if="task">
        <!-- 操作按钮行 -->
        <div class="d-actions">
          <el-button v-if="canAccept" type="primary" :loading="acting" @click="handleAccept">
            受理
          </el-button>
          <el-button
            v-if="canUpdateProgress"
            :type="canAccept ? 'default' : 'primary'"
            @click="openProgress"
          >
            更新进度
          </el-button>
          <el-button
            v-if="canSubmitAcceptance"
            type="primary"
            plain
            :loading="acting"
            @click="handleSubmitAcceptance"
          >
            提交验收
          </el-button>
          <el-button v-if="canApprove" type="primary" :loading="acting" @click="handleApprove">
            验收通过
          </el-button>
          <el-button v-if="canReject" type="danger" link @click="openReject">驳回</el-button>
          <el-button v-if="canTransfer" link type="primary" @click="openTransfer">转派</el-button>
          <el-button v-if="canPriority" link type="primary" @click="openPriority">
            调整优先级
          </el-button>
          <el-button v-if="canDue" link type="primary" @click="openDue">调整到期</el-button>
          <el-button v-if="canArchive" link type="primary" :loading="acting" @click="handleArchive">
            归档
          </el-button>
          <span v-if="!hasAnyAction" class="no-action-tip">当前状态下你没有可执行的操作</span>
        </div>

        <!-- 字段区：双列栅格 -->
        <div class="d-sec">
          <div class="fields">
            <div class="field">
              <div class="f-label">任务类型</div>
              <div class="f-value">{{ task.taskType }}</div>
            </div>
            <div class="field">
              <div class="f-label">优先级</div>
              <div class="f-value">
                <span :style="priorityStyleOf(task.priority)">{{ task.priority }}</span>
              </div>
            </div>
            <div class="field">
              <div class="f-label">创建人</div>
              <div class="f-value">{{ task.creatorName }}</div>
            </div>
            <div class="field">
              <div class="f-label">处理人</div>
              <div class="f-value">{{ task.assigneeName }}</div>
            </div>
            <div class="field">
              <div class="f-label">创建时间</div>
              <div class="f-value tf-num">{{ formatDateTime(task.createdAt) }}</div>
            </div>
            <div class="field">
              <div class="f-label">到期时间</div>
              <div class="f-value" :class="{ 'is-overdue': overdue }">
                <span class="tf-num">{{ formatDateTime(task.dueAt) }}</span>
                <span v-if="overdue" class="tag-overdue">已逾期 {{ overdueDays }} 天</span>
              </div>
            </div>
            <div class="field">
              <div class="f-label">来源渠道</div>
              <div class="f-value">{{ taskSourceName(task.source) }}</div>
            </div>
            <div class="field">
              <div class="f-label">父任务</div>
              <div class="f-value">
                <span v-if="task.parentTaskNo" class="tf-num">{{ task.parentTaskNo }}</span>
                <span v-else class="muted">无</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 任务描述 -->
        <div v-if="task.description" class="d-sec desc">
          <div class="sec-title">任务描述</div>
          <p>{{ task.description }}</p>
        </div>

        <!-- 进度 -->
        <div class="d-sec">
          <div class="sec-title">任务进度</div>
          <div class="progress-top">
            <span class="progress-pct tf-num">{{ task.progress }}%</span>
            <span v-if="latestProgressNote" class="progress-note">
              最近进展：{{ latestProgressNote }}
            </span>
          </div>
          <div class="bar"><i :style="{ width: task.progress + '%' }"></i></div>
        </div>

        <!-- 子任务（PRD 4.1.7：父任务详情展示子任务清单 + 快捷创建） -->
        <div v-if="!task.parentId" class="d-sec subtask-sec">
          <div class="sec-title">
            子任务（{{ subtasks.length }}）
            <el-button
              v-if="userStore.hasPerm('create') && task.status !== 'close'"
              link
              type="primary"
              class="subtask-add"
              @click="emit('createSubtask', task)"
              >＋ 新建子任务</el-button
            >
          </div>
          <div v-if="subtasks.length" class="subtask-list">
            <div
              v-for="st in subtasks"
              :key="st.id"
              class="subtask-item"
              @click="emit('openSubtask', st.id)"
            >
              <span class="st-no tf-num">{{ st.taskNo }}</span>
              <span class="st-title">{{ st.title }}</span>
              <span class="st-status">
                <i class="st-dot" :style="{ backgroundColor: taskStatusMeta(st.status).color }"></i>
                {{ taskStatusMeta(st.status).name }}
              </span>
              <span class="st-assignee">{{ st.assigneeName || '—' }}</span>
              <span class="st-progress tf-num">{{ st.progress }}%</span>
            </div>
          </div>
          <div v-else class="timeline-empty">暂无子任务</div>
        </div>

        <!-- 操作时间线（倒序，竖线 + 色点） -->
        <div class="d-sec timeline-sec">
          <div class="sec-title">操作时间线</div>
          <div v-if="timeline.length" class="timeline">
            <div v-for="item in timeline" :key="item.id" class="tl-item">
              <span class="tl-dot" :style="{ backgroundColor: timelineDotColor(item.action) }"></span>
              <div class="tl-head">
                <span class="tl-action">{{ item.action }}</span>
                <span class="tl-actor">{{ item.operatorName }}</span>
                <span class="tl-time tf-num">{{ formatDateTime(item.createdAt) }}</span>
              </div>
              <div v-if="item.note" class="tl-note">{{ item.note }}</div>
            </div>
          </div>
          <div v-else class="timeline-empty">暂无操作记录</div>
        </div>
      </template>
    </div>

    <!-- 更新进度 -->
    <el-dialog v-model="progressVisible" title="更新进度" width="460px" align-center append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="`进度（0-100，步进 5）：${progressForm.progress}%`">
          <el-slider
            v-model="progressForm.progress"
            :min="0"
            :max="100"
            :step="5"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="进展说明">
          <el-input
            v-model="progressForm.note"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="说明本次进展（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="progressVisible = false">取消</el-button>
        <el-button type="primary" :loading="progressSubmitting" @click="submitProgress">
          更新进度
        </el-button>
      </template>
    </el-dialog>

    <!-- 驳回 -->
    <el-dialog v-model="rejectVisible" title="驳回验收" width="460px" align-center append-to-body>
      <p class="dialog-desc">驳回后任务退回「进行中」，处理人可继续推进后再次提交验收。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="驳回原因（必填，500 字以内）" required>
          <el-input
            v-model="rejectReason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="说明驳回原因，便于处理人修正"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="submitReject">驳回</el-button>
      </template>
    </el-dialog>

    <!-- 优先级 / 到期 / 转派（共用弹窗组） -->
    <TaskActionDialogs
      ref="actionDialogsRef"
      :user-options="userOptions"
      @changed="handleActionChanged"
    />
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  min-height: 100%;
}

/* 标题栏：墨青 48px */
.d-head {
  height: 48px;
  background: #12242E;
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 12px;
  position: sticky;
  top: 0;
  z-index: 5;
}
.d-close {
  width: 28px;
  height: 28px;
  flex: none;
  border: none;
  border-radius: 6px;
  background: none;
  color: rgba(255, 255, 255, 0.7);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.d-close:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #FFFFFF;
}
.d-id {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.55);
  letter-spacing: 0.3px;
}
.d-title {
  font-size: 16px;
  font-weight: 600;
  color: #FFFFFF;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.d-status {
  margin-left: auto;
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  border-radius: 12px;
  background: #E3F2F3;
  color: #0E7C86;
  font-size: 12px;
  font-weight: 600;
}
.d-status i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

/* 操作按钮行 */
.d-actions {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 20px;
  background: #FFFFFF;
  border-bottom: 1px solid #D8DEE6;
  position: sticky;
  top: 48px;
  z-index: 5;
  flex-wrap: wrap;
}
.d-actions .el-button {
  margin-left: 0;
}
.no-action-tip {
  font-size: 13px;
  color: #8A97A8;
}

/* 分区 */
.d-sec {
  padding: 20px 24px;
  border-bottom: 1px solid #E8ECF1;
}
.sec-title {
  font-size: 14px;
  font-weight: 600;
  color: #1F2D3D;
  margin-bottom: 14px;
}

/* 子任务清单 */
.subtask-sec .sec-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.subtask-add {
  font-size: 13px;
}
.subtask-list {
  border: 1px solid #E8ECF1;
  border-radius: 6px;
  overflow: hidden;
}
.subtask-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  font-size: 13px;
  cursor: pointer;
  border-bottom: 1px solid #E8ECF1;
}
.subtask-item:last-child {
  border-bottom: none;
}
.subtask-item:hover {
  background: #F6F7F9;
}
.st-no {
  color: #8A97A8;
  width: 96px;
  flex-shrink: 0;
}
.st-title {
  flex: 1;
  color: #1F2D3D;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.st-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #5E6D82;
  width: 80px;
  flex-shrink: 0;
}
.st-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.st-assignee {
  color: #5E6D82;
  width: 64px;
  flex-shrink: 0;
}
.st-progress {
  color: #5E6D82;
  width: 44px;
  text-align: right;
  flex-shrink: 0;
}

/* 字段区双列 */
.fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 32px;
  row-gap: 14px;
}
.f-label {
  font-size: 12px;
  color: #8A97A8;
  margin-bottom: 2px;
}
.f-value {
  font-size: 14px;
  color: #1F2D3D;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.f-value.is-overdue {
  color: #C8493F;
  font-weight: 500;
}
.f-value .muted {
  color: #8A97A8;
}
.tag-overdue {
  font-size: 12px;
  font-weight: 500;
  color: #C8493F;
  background: #FBEAE8;
  border-radius: 4px;
  padding: 1px 6px;
  line-height: 18px;
}

/* 描述 */
.desc p {
  font-size: 14px;
  color: #5E6D82;
  line-height: 1.8;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 进度 */
.progress-top {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
}
.progress-pct {
  font-size: 28px;
  font-weight: 600;
  color: #0E7C86;
  line-height: 1;
}
.progress-note {
  font-size: 13px;
  color: #5E6D82;
}
.bar {
  height: 8px;
  border-radius: 4px;
  background: #E8ECF1;
  overflow: hidden;
}
.bar > i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: #0E7C86;
}

/* 时间线 */
.timeline-sec {
  border-bottom: none;
}
.timeline {
  position: relative;
  padding-left: 20px;
}
.timeline::before {
  content: "";
  position: absolute;
  left: 5px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: #E8ECF1;
}
.tl-item {
  position: relative;
  padding: 0 0 18px 14px;
}
.tl-item:last-child {
  padding-bottom: 0;
}
.tl-dot {
  position: absolute;
  left: -19px;
  top: 6px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  box-shadow: 0 0 0 3px #FFFFFF;
}
.tl-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.tl-action {
  font-size: 14px;
  font-weight: 600;
  color: #1F2D3D;
}
.tl-actor {
  font-size: 13px;
  color: #5E6D82;
}
.tl-time {
  margin-left: auto;
  font-size: 12px;
  color: #8A97A8;
}
.tl-note {
  font-size: 13px;
  color: #5E6D82;
  margin-top: 3px;
  line-height: 1.7;
  word-break: break-word;
}
.timeline-empty {
  font-size: 13px;
  color: #8A97A8;
  padding: 8px 0;
}

.dialog-desc {
  font-size: 13px;
  color: #5E6D82;
  margin: 0 0 16px;
}
.full-width {
  width: 100%;
}
</style>

<!-- el-drawer 全局样式（非 scoped）：去掉默认 body 内边距、遮罩按规范 4.2 -->
<style>
.task-detail-drawer {
  --el-drawer-padding-primary: 0;
}
.task-detail-drawer .el-drawer__body {
  padding: 0;
}
</style>
