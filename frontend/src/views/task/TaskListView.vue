<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  TASK_PRIORITIES,
  TASK_STATUS_OPTIONS,
  TASK_TYPES,
  createTaskApi,
  fetchTasksApi,
  isTaskOverdue,
  priorityStyleOf,
  taskStatusMeta,
  type TaskItem,
  type TaskPriority,
  type TaskQuery,
  type TaskScope,
  type TaskStatus,
} from '../../api/task'
import { resolveApiError } from '../../api/auth'
import { formatDateTime } from '../../utils/format'
import { useUserStore } from '../../stores/user'
import { useTaskUserOptions } from '../../composables/useTaskUserOptions'
import TaskActionDialogs from '../../components/TaskActionDialogs.vue'
import TaskDetailDrawer from '../../components/TaskDetailDrawer.vue'

/**
 * 任务列表页（M2）：筛选栏（关键字 / 状态 / 优先级 / 类型 / 创建人 / 处理人 / 范围分段）
 * + 任务表格（v-col-resizable 列宽拖拽，行内操作悬停显现）+ 新建任务弹窗 + 详情抽屉。
 * 接口：task-service 4.1（GET/POST /task/api/v1/tasks）；权限点见 UI 规范 5.1。
 */

const userStore = useUserStore()

// ---------- 人员选项（创建人 / 处理人筛选与表单共用；无 manageUser 权限时降级为「我」） ----------
const { userOptions, degraded, loadUserOptions } = useTaskUserOptions()

// ---------- 筛选栏 ----------
const filters = reactive<{
  keyword: string
  status: TaskStatus | ''
  priority: TaskPriority | ''
  taskType: string
  creatorId: number | undefined
  assigneeId: number | undefined
  scope: TaskScope
}>({
  keyword: '',
  status: '',
  priority: '',
  taskType: '',
  creatorId: undefined,
  assigneeId: undefined,
  scope: 'all',
})

const scopeTabs: { value: TaskScope; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'mine', label: '我创建的' },
  { value: 'assigned', label: '指派给我的' },
  { value: 'overdue', label: '已逾期' },
]

// ---------- 列表 ----------
const loading = shallowRef(false)
const taskList = ref<TaskItem[]>([])
const total = shallowRef(0)
const page = shallowRef(1)
const size = shallowRef(20)

async function loadTasks() {
  loading.value = true
  try {
    const params: TaskQuery = { page: page.value, size: size.value }
    if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
    if (filters.status) params.status = filters.status
    if (filters.priority) params.priority = filters.priority
    if (filters.taskType) params.taskType = filters.taskType
    if (filters.creatorId) params.creatorId = filters.creatorId
    if (filters.assigneeId) params.assigneeId = filters.assigneeId
    if (filters.scope !== 'all') params.scope = filters.scope
    const data = await fetchTasksApi(params)
    taskList.value = data.list
    total.value = data.total
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

/** 筛选变更：回到第 1 页并重新加载 */
function handleFilterChange() {
  page.value = 1
  loadTasks()
}

// 关键字输入防抖 400ms
let keywordTimer: number | undefined
watch(
  () => filters.keyword,
  () => {
    window.clearTimeout(keywordTimer)
    keywordTimer = window.setTimeout(handleFilterChange, 400)
  },
)

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.priority = ''
  filters.taskType = ''
  filters.creatorId = undefined
  filters.assigneeId = undefined
  filters.scope = 'all'
  handleFilterChange()
}

onMounted(() => {
  loadTasks()
  loadUserOptions()
})

// ---------- 新建任务（POST /tasks，权限点 create） ----------
const createVisible = shallowRef(false)
const createSubmitting = shallowRef(false)
const createFormRef = useTemplateRef<FormInstance>('createFormRef')

/** 默认到期时间：3 天后 18:00（本地东八区，提交时转 ISO UTC） */
function defaultDueAt(): Date {
  const d = new Date()
  d.setDate(d.getDate() + 3)
  d.setHours(18, 0, 0, 0)
  return d
}

const createForm = reactive<{
  title: string
  description: string
  taskType: string
  priority: TaskPriority
  assigneeId: number | undefined
  dueAt: Date | null
}>({
  title: '',
  description: '',
  taskType: TASK_TYPES[0],
  priority: 'P2',
  assigneeId: undefined,
  dueAt: defaultDueAt(),
})

const createRules: FormRules = {
  title: [
    { required: true, message: '请输入任务标题', trigger: 'blur' },
    { max: 100, message: '标题不超过 100 字', trigger: 'blur' },
  ],
  description: [{ max: 2000, message: '描述不超过 2000 字', trigger: 'blur' }],
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  assigneeId: [{ required: true, message: '请选择处理人', trigger: 'change' }],
  dueAt: [{ required: true, message: '请选择到期时间', trigger: 'change' }],
}

function openCreate() {
  createForm.title = ''
  createForm.description = ''
  createForm.taskType = TASK_TYPES[0]
  createForm.priority = 'P2'
  // 人员选项降级时默认指派给自己
  createForm.assigneeId = degraded.value ? userStore.userInfo?.id : undefined
  createForm.dueAt = defaultDueAt()
  createVisible.value = true
}

async function submitCreate() {
  const form = createFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return
  createSubmitting.value = true
  try {
    await createTaskApi({
      title: createForm.title.trim(),
      description: createForm.description.trim() || undefined,
      taskType: createForm.taskType,
      priority: createForm.priority,
      assigneeId: createForm.assigneeId!,
      dueAt: createForm.dueAt ? createForm.dueAt.toISOString() : undefined,
    })
    createVisible.value = false
    ElMessage.success('已创建任务')
    handleFilterChange()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    createSubmitting.value = false
  }
}

// ---------- 行内操作 ----------
/** 已完成 / 已归档任务锁定优先级、到期、转派操作（原型为禁用灰） */
function opsLocked(row: TaskItem): boolean {
  return row.status === 'done' || row.status === 'close'
}

const canTransfer = computed(
  () => userStore.hasPerm('transferOwn') || userStore.hasPerm('transferAssigned'),
)

const actionDialogsRef = useTemplateRef<InstanceType<typeof TaskActionDialogs>>('actionDialogsRef')

const openPriority = (row: TaskItem) => actionDialogsRef.value?.openPriority(row)
const openDue = (row: TaskItem) => actionDialogsRef.value?.openDue(row)
const openTransfer = (row: TaskItem) => actionDialogsRef.value?.openTransfer(row)

// ---------- 详情抽屉 ----------
const drawerVisible = shallowRef(false)
const drawerTaskId = shallowRef<number | null>(null)

function openDetail(row: TaskItem) {
  drawerTaskId.value = row.id
  drawerVisible.value = true
}

/** 抽屉或快捷操作成功后刷新列表 */
function handleChanged() {
  loadTasks()
}
</script>

<template>
  <div class="task-list-view">
    <!-- 页面标题行 -->
    <div class="page-head">
      <div class="page-title">
        <h1>任务列表</h1>
        <p>查看与管理团队全部任务，支持按状态、优先级、成员等维度筛选。</p>
      </div>
      <div class="page-actions">
        <el-tooltip content="后续里程碑开放" placement="bottom">
          <span>
            <el-button v-perm="'exportData'" disabled>导出 CSV</el-button>
          </span>
        </el-tooltip>
        <el-button v-perm="'create'" type="primary" @click="openCreate">＋ 新建任务</el-button>
      </div>
    </div>

    <!-- 筛选栏（白卡） -->
    <div class="tf-card filter-bar">
      <div class="filter-row">
        <div class="segmented">
          <button
            v-for="tab in scopeTabs"
            :key="tab.value"
            type="button"
            class="seg"
            :class="{ active: filters.scope === tab.value }"
            @click="filters.scope = tab.value; handleFilterChange()"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div class="filter-row">
        <el-input
          v-model="filters.keyword"
          class="keyword-input"
          placeholder="编号 / 标题 / 描述 / 姓名"
          clearable
          @clear="handleFilterChange"
          @keyup.enter="handleFilterChange"
        />
        <el-select
          v-model="filters.status"
          placeholder="状态"
          clearable
          class="filter-select"
          @change="handleFilterChange"
        >
          <el-option
            v-for="s in TASK_STATUS_OPTIONS"
            :key="s.value"
            :label="s.name"
            :value="s.value"
          />
        </el-select>
        <el-select
          v-model="filters.priority"
          placeholder="优先级"
          clearable
          class="filter-select"
          @change="handleFilterChange"
        >
          <el-option v-for="p in TASK_PRIORITIES" :key="p" :label="p" :value="p" />
        </el-select>
        <el-select
          v-model="filters.taskType"
          placeholder="任务类型"
          clearable
          class="filter-select"
          @change="handleFilterChange"
        >
          <el-option v-for="t in TASK_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select
          v-model="filters.creatorId"
          placeholder="创建人"
          clearable
          filterable
          class="filter-select"
          @change="handleFilterChange"
        >
          <el-option v-for="u in userOptions" :key="u.id" :label="u.name" :value="u.id" />
        </el-select>
        <el-select
          v-model="filters.assigneeId"
          placeholder="处理人"
          clearable
          filterable
          class="filter-select"
          @change="handleFilterChange"
        >
          <el-option v-for="u in userOptions" :key="u.id" :label="u.name" :value="u.id" />
        </el-select>
        <el-button link type="primary" class="filter-reset" @click="resetFilters">
          重置筛选
        </el-button>
      </div>
    </div>

    <!-- 任务表格 -->
    <div class="tf-card table-card">
      <el-table v-loading="loading" v-col-resizable :data="taskList">
        <el-table-column label="编号" width="110">
          <template #default="{ row }">
            <span class="tf-num col-no">{{ row.taskNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.parentTaskNo" class="sub-prefix"
              >↳ <span class="tf-num">[{{ row.parentTaskNo }}]</span>&nbsp;</span
            ><span>{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="type-tag">{{ row.taskType }}</span>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="80">
          <template #default="{ row }">
            <span :style="priorityStyleOf(row.priority)">{{ row.priority }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :style="{ color: taskStatusMeta(row.status).textColor }">
              <span
                class="tf-dot"
                :style="{ backgroundColor: taskStatusMeta(row.status).color }"
              ></span
              >{{ taskStatusMeta(row.status).name }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="创建人" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.creatorName || '—' }}</template>
        </el-table-column>
        <el-table-column label="处理人" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.assigneeName || '—' }}</template>
        </el-table-column>
        <el-table-column label="到期时间" width="150">
          <template #default="{ row }">
            <span class="tf-num due" :class="{ overdue: isTaskOverdue(row) }">{{
              formatDateTime(row.dueAt)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="120">
          <template #default="{ row }">
            <div class="progress-cell">
              <div class="progress-bar">
                <div
                  class="fill"
                  :class="{ full: row.progress === 100 }"
                  :style="{ width: row.progress + '%' }"
                ></div>
              </div>
              <span class="progress-num tf-num">{{ row.progress }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            <span class="tf-num upd-time">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="row-ops">
              <el-button
                v-if="userStore.hasPerm('prioOwn')"
                link
                type="primary"
                :disabled="opsLocked(row)"
                @click="openPriority(row)"
                >优先级</el-button
              >
              <el-button
                v-if="userStore.hasPerm('dueOwn')"
                link
                type="primary"
                :disabled="opsLocked(row)"
                @click="openDue(row)"
                >到期</el-button
              >
              <el-button
                v-if="canTransfer"
                link
                type="primary"
                :disabled="opsLocked(row)"
                @click="openTransfer(row)"
                >转派</el-button
              >
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-tip">
            还没有符合条件的任务，调整筛选条件，或点击右上角「新建任务」创建第一个。
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadTasks"
          @size-change="handleFilterChange"
        />
      </div>
    </div>

    <!-- 新建任务 -->
    <el-dialog
      v-model="createVisible"
      title="新建任务"
      width="520px"
      align-center
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="标题（必填，100 字以内）" prop="title">
          <el-input
            v-model="createForm.title"
            maxlength="100"
            show-word-limit
            placeholder="请输入任务标题"
          />
        </el-form-item>
        <el-form-item label="描述（2000 字以内）" prop="description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="背景、目标与验收标准（可选）"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="任务类型" prop="taskType">
            <el-select v-model="createForm.taskType" class="full-width">
              <el-option v-for="t in TASK_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级" prop="priority">
            <el-select v-model="createForm.priority" class="full-width">
              <el-option v-for="p in TASK_PRIORITIES" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="处理人（必选）" prop="assigneeId">
          <el-select
            v-model="createForm.assigneeId"
            placeholder="请选择处理人"
            class="full-width"
            filterable
          >
            <el-option v-for="u in userOptions" :key="u.id" :label="u.name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到期时间" prop="dueAt">
          <el-date-picker
            v-model="createForm.dueAt"
            type="datetime"
            placeholder="请选择到期时间"
            class="full-width"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">
          新建任务
        </el-button>
      </template>
    </el-dialog>

    <!-- 行内快捷操作弹窗（优先级 / 到期 / 转派） -->
    <TaskActionDialogs
      ref="actionDialogsRef"
      :user-options="userOptions"
      @changed="handleChanged"
    />

    <!-- 任务详情抽屉 -->
    <TaskDetailDrawer v-model="drawerVisible" :task-id="drawerTaskId" @changed="handleChanged" />
  </div>
</template>

<style scoped>
/* 页面标题行 */
.page-head {
  display: flex;
  align-items: flex-end;
  margin-bottom: 16px;
}
.page-title h1 {
  font-size: 20px;
  font-weight: 600;
  color: #12242E;
  margin: 0;
}
.page-title p {
  font-size: 13px;
  color: #8A97A8;
  margin: 6px 0 0;
}
.page-actions {
  margin-left: auto;
  display: flex;
  gap: 12px;
}

/* 筛选栏 */
.filter-bar {
  padding: 16px;
  margin-bottom: 16px;
}
.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-row + .filter-row {
  margin-top: 12px;
}
.keyword-input {
  width: 240px;
}
.filter-select {
  width: 128px;
}
.filter-reset {
  margin-left: 4px;
}

/* 范围分段控件 */
.segmented {
  display: inline-flex;
  background: #F6F7F9;
  border: 1px solid #D8DEE6;
  border-radius: 6px;
  padding: 2px;
}
.segmented .seg {
  height: 28px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #5E6D82;
  border: none;
  border-radius: 4px;
  background: none;
  cursor: pointer;
  font-family: inherit;
  transition: color 120ms ease-out;
}
.segmented .seg:hover {
  color: #1F2D3D;
}
.segmented .seg.active {
  background: #0E7C86;
  color: #FFFFFF;
}

/* 表格单元格元素 */
.col-no {
  color: #5E6D82;
  font-size: 13px;
}
.sub-prefix {
  color: #8A97A8;
}
.type-tag {
  display: inline-block;
  height: 22px;
  line-height: 20px;
  padding: 0 8px;
  border: 1px solid #D8DEE6;
  border-radius: 4px;
  font-size: 12px;
  color: #5E6D82;
  background: #FFFFFF;
}
.due {
  font-size: 13px;
  color: #5E6D82;
}
.due.overdue {
  color: #C8493F;
  font-weight: 500;
}
.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.progress-bar {
  width: 56px;
  height: 4px;
  background: #E8ECF1;
  border-radius: 2px;
  overflow: hidden;
  flex: 0 0 56px;
}
.progress-bar .fill {
  height: 100%;
  background: #0E7C86;
  border-radius: 2px;
}
.progress-bar .fill.full {
  background: #2F9E6E;
}
.progress-num {
  font-size: 12px;
  color: #5E6D82;
}
.upd-time {
  font-size: 13px;
  color: #8A97A8;
}

/* 行内操作：悬停行显现 */
.row-ops {
  opacity: 0;
  transition: opacity 120ms ease-out;
  white-space: nowrap;
}
:deep(.el-table__row:hover) .row-ops {
  opacity: 1;
}
.row-ops .el-button + .el-button {
  margin-left: 8px;
}

/* 分页右下 */
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.empty-tip {
  color: #8A97A8;
  font-size: 13px;
  padding: 32px 0;
}

/* 新建弹窗双列表单项 */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}
.full-width {
  width: 100%;
}
</style>
