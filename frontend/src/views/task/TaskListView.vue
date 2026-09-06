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
import { fetchDepartmentsApi, resolveApiError, type DepartmentItem } from '../../api/auth'
import { formatDateTime } from '../../utils/format'
import { useUserStore } from '../../stores/user'
import { useTaskUserOptions } from '../../composables/useTaskUserOptions'
import TaskActionDialogs from '../../components/TaskActionDialogs.vue'
import TaskDetailDrawer from '../../components/TaskDetailDrawer.vue'

/**
 * 任务列表页（M2）：筛选栏（关键字 / 状态 / 优先级 / 类型 / 创建人 / 处理人 / 处理人部门 / 范围分段）
 * + 树形任务表格（顶层任务分页，子任务懒加载展开；v-col-resizable 列宽拖拽，行内操作悬停显现）
 * + 新建任务弹窗 + 详情抽屉。
 * 接口：task-service 4.1（GET/POST /task/api/v1/tasks）；权限点见 UI 规范 5.1。
 */

const userStore = useUserStore()

// ---------- 人员选项（创建人 / 处理人筛选与表单共用，users/lookup 登录即可） ----------
const { userOptions, loadUserOptions } = useTaskUserOptions()

// ---------- 部门选项（处理人部门筛选，登录即可） ----------
const departments = ref<DepartmentItem[]>([])

// ---------- 筛选栏 ----------
const filters = reactive<{
  keyword: string
  status: TaskStatus | ''
  priority: TaskPriority | ''
  taskType: string
  creatorId: number | undefined
  assigneeId: number | undefined
  assigneeDeptId: number | undefined
  scope: TaskScope
}>({
  keyword: '',
  status: '',
  priority: '',
  taskType: '',
  creatorId: undefined,
  assigneeId: undefined,
  assigneeDeptId: undefined,
  scope: 'all',
})

const scopeTabs: { value: TaskScope; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'mine', label: '我创建的' },
  { value: 'assigned', label: '指派给我的' },
  { value: 'overdue', label: '已逾期' },
]

// ---------- 列设置（勾选持久化 localStorage；编号 / 标题 / 操作为必选列） ----------
interface ColumnConfig {
  key: string
  label: string
  width?: number
  minWidth?: number
  fixed?: 'right'
  /** 单元格溢出省略 + tooltip */
  tooltip?: boolean
  defaultVisible: boolean
  /** 必选列：不可取消勾选 */
  required?: boolean
}

const COLUMN_CONFIGS: ColumnConfig[] = [
  { key: 'taskNo', label: '编号', width: 120, defaultVisible: true, required: true },
  { key: 'title', label: '标题', minWidth: 240, tooltip: true, defaultVisible: true, required: true },
  { key: 'taskType', label: '类型', width: 110, defaultVisible: true },
  { key: 'priority', label: '优先级', width: 80, defaultVisible: true },
  { key: 'status', label: '状态', width: 100, defaultVisible: true },
  { key: 'creatorName', label: '创建人', width: 90, tooltip: true, defaultVisible: true },
  { key: 'assigneeName', label: '处理人', width: 90, tooltip: true, defaultVisible: true },
  { key: 'assigneeDeptName', label: '处理人部门', width: 120, tooltip: true, defaultVisible: true },
  { key: 'dueAt', label: '到期时间', width: 170, defaultVisible: true },
  { key: 'progress', label: '进度', width: 120, defaultVisible: true },
  { key: 'updatedAt', label: '更新时间', width: 150, defaultVisible: true },
  { key: 'ops', label: '操作', width: 130, fixed: 'right', defaultVisible: true, required: true },
]

const COLUMN_STORAGE_KEY = 'taskflow.tasklist.columns'

/** 读取持久化的可见列；必选列始终并入，未知 key 丢弃（兼容列配置演进） */
function loadVisibleColumnKeys(): string[] {
  const defaults = COLUMN_CONFIGS.filter((c) => c.defaultVisible).map((c) => c.key)
  const required = COLUMN_CONFIGS.filter((c) => c.required).map((c) => c.key)
  try {
    const raw = localStorage.getItem(COLUMN_STORAGE_KEY)
    if (!raw) return defaults
    const saved = JSON.parse(raw) as unknown
    if (!Array.isArray(saved)) return defaults
    const valid = (saved as unknown[]).filter(
      (k): k is string => typeof k === 'string' && COLUMN_CONFIGS.some((c) => c.key === k),
    )
    return [...new Set([...required, ...valid])]
  } catch {
    return defaults
  }
}

const visibleColumnKeys = ref<string[]>(loadVisibleColumnKeys())

const visibleColumns = computed(() =>
  COLUMN_CONFIGS.filter((c) => visibleColumnKeys.value.includes(c.key)),
)

watch(
  visibleColumnKeys,
  (keys) => {
    localStorage.setItem(COLUMN_STORAGE_KEY, JSON.stringify(keys))
  },
  { deep: true },
)

// ---------- 列表 ----------
const loading = shallowRef(false)
const taskList = ref<TaskItem[]>([])
const total = shallowRef(0)
const page = shallowRef(1)
const size = shallowRef(20)
/** 每次主查询成功后递增，强制重建表格以清空懒加载子任务缓存（防止筛选/操作后展示过期子任务） */
const tableEpoch = shallowRef(0)

/** 当前筛选条件 → 接口参数（主查询与子任务懒加载共用，筛选对两层都生效） */
function buildFilterParams(): Omit<TaskQuery, 'page' | 'size'> {
  const params: Omit<TaskQuery, 'page' | 'size'> = {}
  if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
  if (filters.status) params.status = filters.status
  if (filters.priority) params.priority = filters.priority
  if (filters.taskType) params.taskType = filters.taskType
  if (filters.creatorId) params.creatorId = filters.creatorId
  if (filters.assigneeId) params.assigneeId = filters.assigneeId
  if (filters.assigneeDeptId) params.assigneeDeptId = filters.assigneeDeptId
  if (filters.scope !== 'all') params.scope = filters.scope
  return params
}

async function loadTasks() {
  loading.value = true
  try {
    const data = await fetchTasksApi({
      ...buildFilterParams(),
      topLevel: true,
      page: page.value,
      size: size.value,
    })
    // hasChildren 由后端返回：真实有子任务的顶层行才显示展开箭头
    taskList.value = data.list
    total.value = data.total
    tableEpoch.value++
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

/** 树形表格懒加载子任务：GET /tasks?parentId={row.id}（不分页，筛选条件同样作用于子任务层） */
function loadChildren(row: TaskItem, _treeNode: unknown, resolve: (data: TaskItem[]) => void) {
  fetchTasksApi({ ...buildFilterParams(), parentId: row.id, page: 1, size: 50 })
    .then((data) => resolve(data.list))
    .catch((error) => {
      ElMessage.error(resolveApiError(error).message)
      resolve([])
    })
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
  filters.assigneeDeptId = undefined
  filters.scope = 'all'
  handleFilterChange()
}

onMounted(() => {
  loadTasks()
  loadUserOptions()
  fetchDepartmentsApi()
    .then((list) => {
      departments.value = list
    })
    .catch((error) => {
      ElMessage.error(resolveApiError(error).message)
    })
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

/** 子任务模式：非空表示正在为某个父任务创建子任务（类型继承父任务，PRD 4.1.7） */
const createParent = shallowRef<TaskItem | null>(null)

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
  createParent.value = null
  createForm.title = ''
  createForm.description = ''
  createForm.taskType = TASK_TYPES[0]
  createForm.priority = 'P2'
  createForm.assigneeId = undefined
  createForm.dueAt = defaultDueAt()
  createVisible.value = true
}

/** 在父任务下新建子任务（列表行内"添加子任务"与详情抽屉入口共用） */
function openSubtaskCreate(parent: TaskItem) {
  createParent.value = parent
  createForm.title = ''
  createForm.description = ''
  createForm.taskType = parent.taskType // 类型继承父任务（表单中禁用展示）
  createForm.priority = parent.priority
  createForm.assigneeId = parent.assigneeId
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
      parentId: createParent.value?.id, // 子任务模式：挂在父任务下
    })
    createVisible.value = false
    ElMessage.success(createParent.value ? '已创建子任务' : '已创建任务')
    // 子任务创建成功且抽屉打开着父任务：强制抽屉重新拉取（子任务清单刷新）
    if (createParent.value && drawerVisible.value) {
      drawerEpoch.value++
    }
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

/** 「更多」下拉整体显隐：四个动作权限一个都没有时不渲染 */
const hasRowMoreActions = computed(
  () =>
    userStore.hasPerm('prioOwn') ||
    userStore.hasPerm('dueOwn') ||
    canTransfer.value ||
    userStore.hasPerm('create'),
)

const actionDialogsRef = useTemplateRef<InstanceType<typeof TaskActionDialogs>>('actionDialogsRef')

const openPriority = (row: TaskItem) => actionDialogsRef.value?.openPriority(row)
const openDue = (row: TaskItem) => actionDialogsRef.value?.openDue(row)
const openTransfer = (row: TaskItem) => actionDialogsRef.value?.openTransfer(row)

// ---------- 详情抽屉 ----------
const drawerVisible = shallowRef(false)
const drawerTaskId = shallowRef<number | null>(null)
/** 抽屉重载计数：子任务创建成功等场景强制抽屉重新拉取详情 */
const drawerEpoch = shallowRef(0)

function openDetail(row: TaskItem) {
  drawerTaskId.value = row.id
  drawerVisible.value = true
}

/** 抽屉内点击子任务：切换抽屉到该子任务详情 */
function openSubtaskDetail(taskId: number) {
  drawerTaskId.value = taskId
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
        <el-select
          v-model="filters.assigneeDeptId"
          placeholder="处理人部门"
          clearable
          filterable
          class="filter-select filter-select--dept"
          @change="handleFilterChange"
        >
          <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button link type="primary" class="filter-reset" @click="resetFilters">
          重置筛选
        </el-button>
        <!-- 列设置：勾选面板，必选列禁用，勾选状态持久化 localStorage -->
        <el-popover placement="bottom-end" :width="200" trigger="click">
          <template #reference>
            <el-button class="columns-btn">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.6" />
                <path
                  d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"
                  stroke="currentColor"
                  stroke-width="1.6"
                />
              </svg>
              列设置
            </el-button>
          </template>
          <div class="columns-panel">
            <el-checkbox-group v-model="visibleColumnKeys">
              <el-checkbox
                v-for="c in COLUMN_CONFIGS"
                :key="c.key"
                :value="c.key"
                :disabled="c.required"
              >
                {{ c.label }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </el-popover>
      </div>
    </div>

    <!-- 任务表格 -->
    <div class="tf-card table-card">
      <el-table
        :key="tableEpoch"
        v-loading="loading"
        v-col-resizable
        :data="taskList"
        row-key="id"
        lazy
        :load="loadChildren"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
      >
        <el-table-column
          v-for="col in visibleColumns"
          :key="col.key"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :fixed="col.fixed"
          :show-overflow-tooltip="col.tooltip"
        >
          <template #default="{ row }">
            <span v-if="col.key === 'taskNo'" class="tf-num col-no">{{ row.taskNo }}</span>
            <span v-else-if="col.key === 'title'">
              <span v-if="row.parentTaskNo" class="tf-num parent-no">[{{ row.parentTaskNo }}] </span
              ><span>{{ row.title }}</span>
            </span>
            <span v-else-if="col.key === 'taskType'" class="type-tag">{{ row.taskType }}</span>
            <span v-else-if="col.key === 'priority'" :style="priorityStyleOf(row.priority)">{{
              row.priority
            }}</span>
            <span
              v-else-if="col.key === 'status'"
              :style="{ color: taskStatusMeta(row.status).textColor }"
            >
              <span
                class="tf-dot"
                :style="{ backgroundColor: taskStatusMeta(row.status).color }"
              ></span
              >{{ taskStatusMeta(row.status).name }}
            </span>
            <template v-else-if="col.key === 'creatorName'">{{ row.creatorName || '—' }}</template>
            <template v-else-if="col.key === 'assigneeName'">{{ row.assigneeName || '—' }}</template>
            <template v-else-if="col.key === 'assigneeDeptName'">{{
              row.assigneeDepartmentName || '—'
            }}</template>
            <template v-else-if="col.key === 'dueAt'">
              <span class="tf-num due" :class="{ overdue: isTaskOverdue(row) }">{{
                formatDateTime(row.dueAt)
              }}</span>
              <span v-if="isTaskOverdue(row)" class="overdue-tag">已逾期</span>
            </template>
            <div v-else-if="col.key === 'progress'" class="progress-cell">
              <div class="progress-bar">
                <div
                  class="fill"
                  :class="{ full: row.progress === 100 }"
                  :style="{ width: row.progress + '%' }"
                ></div>
              </div>
              <span class="progress-num tf-num">{{ row.progress }}%</span>
            </div>
            <span v-else-if="col.key === 'updatedAt'" class="tf-num upd-time">{{
              formatDateTime(row.updatedAt)
            }}</span>
            <!-- 操作列：详情 + 更多下拉（按权限显隐，已完成/已归档锁定） -->
            <div v-else-if="col.key === 'ops'" class="row-ops">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-dropdown v-if="hasRowMoreActions" trigger="click">
                <el-button link type="primary" class="more-btn">
                  更多
                  <svg
                    class="caret-icon"
                    width="10"
                    height="10"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      d="m6 9 6 6 6-6"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-if="userStore.hasPerm('prioOwn')"
                      :disabled="opsLocked(row)"
                      @click="openPriority(row)"
                      >调整优先级</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="userStore.hasPerm('dueOwn')"
                      :disabled="opsLocked(row)"
                      @click="openDue(row)"
                      >调整到期时间</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="canTransfer"
                      :disabled="opsLocked(row)"
                      @click="openTransfer(row)"
                      >转派</el-dropdown-item
                    >
                    <el-dropdown-item
                      v-if="!row.parentId && userStore.hasPerm('create')"
                      :disabled="opsLocked(row)"
                      @click="openSubtaskCreate(row)"
                      >添加子任务</el-dropdown-item
                    >
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-tip">
            暂无任务，点击右上角「＋ 新建任务」创建第一个；或调整筛选条件重新查询。
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

    <!-- 新建任务 / 新建子任务 -->
    <el-dialog
      v-model="createVisible"
      :title="createParent ? `新建子任务（父任务：${createParent.taskNo}）` : '新建任务'"
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
            <el-select v-model="createForm.taskType" class="full-width" :disabled="!!createParent">
              <el-option v-for="t in TASK_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
            <div v-if="createParent" class="inherit-tip">子任务类型继承父任务（{{ createParent.taskType }}）</div>
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
          {{ createParent ? '新建子任务' : '新建任务' }}
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
    <TaskDetailDrawer
      :key="drawerEpoch"
      v-model="drawerVisible"
      :task-id="drawerTaskId"
      @changed="handleChanged"
      @create-subtask="openSubtaskCreate"
      @open-subtask="openSubtaskDetail"
    />
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
.filter-select--dept {
  width: 150px;
}
.filter-reset {
  margin-left: 4px;
}

/* 列设置按钮：靠筛选行右端 */
.columns-btn {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.columns-panel :deep(.el-checkbox-group) {
  display: flex;
  flex-direction: column;
}
.columns-panel :deep(.el-checkbox) {
  height: 30px;
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
.parent-no {
  color: #8A97A8;
  font-size: 12px;
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
.overdue-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: #C8493F;
  background: rgba(200, 73, 63, 0.08);
  border-radius: 4px;
}
.inherit-tip {
  font-size: 12px;
  color: #8A97A8;
  margin-top: 4px;
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
.row-ops .el-dropdown {
  margin-left: 8px;
}
.more-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.more-btn .caret-icon {
  flex: none;
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
