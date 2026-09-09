<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  PRIORITY_STYLE,
  fetchTaskCalendarApi,
  taskStatusMeta,
  type CalendarDay,
  type CalendarTaskItem,
} from '../../api/task'
import { resolveApiError } from '../../api/auth'
import { formatDateTime } from '../../utils/format'
import TaskDetailDrawer from '../../components/TaskDetailDrawer.vue'

const router = useRouter()

/**
 * 日程月历页（M4）：自绘月历（周一为一周起点，对齐原型 docs/任务管理系统原型.html 日程视图）。
 * - 月头：上/下月 + 今天 + 月份标题 + 当月任务统计
 * - 每天格子：当日到期任务（优先级色点 + 标题截断，超出 3 条折叠为「+N 更多」）
 * - 点击任务：打开详情抽屉；点击日期：右侧「当日日程」面板列出全部任务
 * - 可见性与任务列表一致（后端已过滤）
 * 接口：GET /task/api/v1/tasks/calendar?month=YYYY-MM
 */

// ---------- 月份状态 ----------
const current = shallowRef(new Date()) // 当前展示月份（取年月）

function monthParam(d: Date): string {
  const m = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  return m
}

const monthLabel = computed(() => `${current.value.getFullYear()} 年 ${current.value.getMonth() + 1} 月`)

function shiftMonth(delta: number) {
  const d = new Date(current.value)
  d.setDate(1)
  d.setMonth(d.getMonth() + delta)
  current.value = d
  loadCalendar()
}

function goToday() {
  current.value = new Date()
  selectedDate.value = todayStr
  loadCalendar()
}

// ---------- 日历数据 ----------
const loading = shallowRef(false)
const days = shallowRef<CalendarDay[]>([])

/** date(YYYY-MM-DD) → 当日任务 */
const taskByDate = computed(() => {
  const map = new Map<string, CalendarTaskItem[]>()
  for (const d of days.value) map.set(d.date, d.tasks ?? [])
  return map
})

const totalInMonth = computed(() =>
  days.value.reduce((sum, d) => sum + (d.tasks?.length ?? 0), 0),
)

async function loadCalendar() {
  loading.value = true
  try {
    days.value = await fetchTaskCalendarApi(monthParam(current.value))
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

onMounted(loadCalendar)

// ---------- 月历栅格（周一起，6 行 × 7 列） ----------
interface CalendarCell {
  /** YYYY-MM-DD（本地时区） */
  date: string
  dayNum: number
  inMonth: boolean
  isToday: boolean
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function toDateStr(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const todayStr = toDateStr(new Date())

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

const cells = computed<CalendarCell[]>(() => {
  const year = current.value.getFullYear()
  const month = current.value.getMonth()
  const first = new Date(year, month, 1)
  // 周一为起点：getDay() 周日=0 → 偏移 6，周一=1 → 偏移 0
  const leading = (first.getDay() + 6) % 7
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const rows = Math.ceil((leading + daysInMonth) / 7)
  const start = new Date(year, month, 1 - leading)
  const list: CalendarCell[] = []
  for (let i = 0; i < rows * 7; i++) {
    const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)
    const date = toDateStr(d)
    list.push({
      date,
      dayNum: d.getDate(),
      inMonth: d.getMonth() === month,
      isToday: date === todayStr,
    })
  }
  return list
})

// ---------- 选中日期与右侧面板 ----------
const selectedDate = shallowRef(todayStr)

const selectedTasks = computed(() => taskByDate.value.get(selectedDate.value) ?? [])

const selectedLabel = computed(() => {
  const [, m, d] = selectedDate.value.split('-')
  return `${Number(m)} 月 ${Number(d)} 日`
})

function selectDay(cell: CalendarCell) {
  selectedDate.value = cell.date
  // 点到相邻月格子时切换到对应月份
  if (!cell.inMonth) {
    const [y, m] = cell.date.split('-').map(Number)
    const d = new Date(y, m - 1, 1)
    if (monthParam(d) !== monthParam(current.value)) {
      current.value = d
      loadCalendar()
    }
  }
}

// ---------- 任务展示 ----------
/** 单元格最多展示条数，超出折叠 */
const CELL_MAX_TASKS = 3

function cellTasks(date: string): CalendarTaskItem[] {
  return taskByDate.value.get(date) ?? []
}

function cellVisibleTasks(date: string): CalendarTaskItem[] {
  return cellTasks(date).slice(0, CELL_MAX_TASKS)
}

function cellMoreCount(date: string): number {
  return Math.max(0, cellTasks(date).length - CELL_MAX_TASKS)
}

function priorityColor(priority: string): string {
  return PRIORITY_STYLE[priority as keyof typeof PRIORITY_STYLE]?.color ?? '#1F2D3D'
}

/** 已完成 / 已归档任务弱化展示 */
function isClosed(t: CalendarTaskItem): boolean {
  return t.status === 'done' || t.status === 'close'
}

// ---------- 详情抽屉 ----------
const drawerVisible = shallowRef(false)
const drawerTaskId = shallowRef<number | null>(null)

function openTask(t: CalendarTaskItem) {
  drawerTaskId.value = t.id
  drawerVisible.value = true
}

/** 抽屉内动作成功后刷新当月 */
function handleChanged() {
  loadCalendar()
}

/** 抽屉内点击子任务：切换抽屉到该子任务详情 */
function openSubtaskDetail(taskId: number) {
  drawerTaskId.value = taskId
}

/** 抽屉内「新建子任务」：日历页无创建表单，跳转任务列表并打开父任务抽屉（列表页已支持创建） */
function handleCreateSubtask(task: { id: number }) {
  router.push({ path: '/tasks', query: { taskId: task.id } })
}
</script>

<template>
  <div class="calendar-view">
    <!-- 页面标题行 -->
    <div class="page-head">
      <div class="page-title">
        <h1>日程</h1>
        <p>按任务「到期时间」排布的月历视图，点击任务可打开详情。</p>
      </div>
    </div>

    <div class="calendar-layout">
      <!-- 月历卡片 -->
      <div class="tf-card cal-card" v-loading="loading">
        <div class="cal-head">
          <div class="cal-nav">
            <button type="button" aria-label="上一月" @click="shiftMonth(-1)">‹</button>
            <button type="button" aria-label="下一月" @click="shiftMonth(1)">›</button>
            <el-button size="small" @click="goToday">今天</el-button>
          </div>
          <div class="cal-title">{{ monthLabel }}</div>
          <span class="cal-stat">当月 {{ totalInMonth }} 项到期任务</span>
        </div>

        <div class="cal-weekdays">
          <div v-for="w in WEEKDAYS" :key="w">{{ w }}</div>
        </div>

        <div class="cal-grid">
          <div
            v-for="cell in cells"
            :key="cell.date"
            class="cal-cell"
            :class="{
              other: !cell.inMonth,
              today: cell.isToday,
              selected: cell.date === selectedDate,
            }"
            @click="selectDay(cell)"
          >
            <span class="dnum">{{ cell.dayNum }}</span>
            <div
              v-for="t in cellVisibleTasks(cell.date)"
              :key="t.id"
              class="ev"
              :class="{ closed: isClosed(t) }"
              :title="`${t.taskNo} ${t.title}（${t.assigneeName || '未指派'}）`"
              @click.stop="openTask(t)"
            >
              <i class="ev-dot" :style="{ backgroundColor: priorityColor(t.priority) }"></i>
              <span class="ev-title">{{ t.title }}</span>
            </div>
            <div
              v-if="cellMoreCount(cell.date) > 0"
              class="ev more"
              @click.stop="selectDay(cell)"
            >
              +{{ cellMoreCount(cell.date) }} 更多
            </div>
          </div>
        </div>
      </div>

      <!-- 当日日程面板 -->
      <div class="tf-card day-panel">
        <div class="panel-head">
          <h3>{{ selectedLabel }} 日程</h3>
          <span class="panel-sub">{{ selectedTasks.length }} 项任务</span>
        </div>
        <div v-if="selectedTasks.length" class="day-list">
          <div
            v-for="t in selectedTasks"
            :key="t.id"
            class="day-item"
            @click="openTask(t)"
          >
            <div class="di-head">
              <span class="di-no tf-num">{{ t.taskNo }}</span>
              <span class="di-priority" :style="PRIORITY_STYLE[t.priority] ?? {}">
                {{ t.priority }}
              </span>
            </div>
            <div class="di-title" :class="{ closed: isClosed(t) }">{{ t.title }}</div>
            <div class="di-meta">
              <span class="di-status">
                <i class="tf-dot" :style="{ backgroundColor: taskStatusMeta(t.status).color }"></i>
                {{ taskStatusMeta(t.status).name }}
              </span>
              <span class="di-assignee">{{ t.assigneeName || '未指派' }}</span>
            </div>
          </div>
        </div>
        <div v-else class="day-empty">当日暂无到期任务</div>
      </div>
    </div>

    <!-- 任务详情抽屉 -->
    <TaskDetailDrawer
      v-model="drawerVisible"
      :task-id="drawerTaskId"
      @changed="handleChanged"
      @open-subtask="openSubtaskDetail"
      @create-subtask="handleCreateSubtask"
    />
  </div>
</template>

<style scoped>
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

.calendar-layout {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 16px;
  align-items: start;
}

/* 月历卡片 */
.cal-card {
  padding: 0;
  overflow: hidden;
}
.cal-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  border-bottom: 1px solid #E8ECF1;
}
.cal-nav {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cal-nav > button {
  width: 30px;
  height: 30px;
  border: 1px solid #D8DEE6;
  border-radius: 6px;
  background: #FFFFFF;
  font-size: 14px;
  color: #5E6D82;
  cursor: pointer;
  display: grid;
  place-items: center;
  font-family: inherit;
}
.cal-nav > button:hover {
  border-color: #0E7C86;
  color: #0E7C86;
}
.cal-title {
  font-size: 16px;
  font-weight: 600;
  color: #12242E;
}
.cal-stat {
  margin-left: auto;
  font-size: 12px;
  color: #8A97A8;
}

.cal-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  background: #F6F7F9;
  border-bottom: 1px solid #D8DEE6;
}
.cal-weekdays div {
  padding: 9px 0;
  text-align: center;
  font-size: 12px;
  color: #5E6D82;
  font-weight: 600;
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
}
.cal-cell {
  min-height: 108px;
  border-right: 1px solid #E8ECF1;
  border-bottom: 1px solid #E8ECF1;
  padding: 5px 6px;
  cursor: pointer;
  transition: background-color 120ms ease-out;
  overflow: hidden;
}
.cal-cell:nth-child(7n) {
  border-right: none;
}
.cal-cell:hover {
  background: #F6F7F9;
}
.cal-cell.other {
  background: #FAFBFC;
}
.cal-cell.other .dnum {
  color: #C3CBD4;
}
.cal-cell.selected {
  background: #E3F2F3;
  box-shadow: inset 0 0 0 2px #0E7C86;
}
.dnum {
  font-size: 12.5px;
  font-weight: 600;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  color: #1F2D3D;
}
.cal-cell.today .dnum {
  background: #0E7C86;
  color: #FFFFFF;
  border-radius: 50%;
}

/* 任务条 */
.ev {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  padding: 2px 6px;
  border-radius: 4px;
  margin-top: 3px;
  background: #F6F7F9;
  color: #1F2D3D;
  line-height: 1.5;
  cursor: pointer;
  overflow: hidden;
}
.ev:hover {
  background: #E3F2F3;
}
.ev-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.ev-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev.closed .ev-title {
  color: #8A97A8;
  text-decoration: line-through;
}
.ev.more {
  background: none;
  color: #8A97A8;
  font-size: 11px;
}
.ev.more:hover {
  color: #0E7C86;
}

/* 当日日程面板 */
.day-panel {
  padding: 16px 18px;
  min-height: 200px;
}
.panel-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}
.panel-head h3 {
  font-size: 15px;
  font-weight: 600;
  color: #12242E;
  margin: 0;
}
.panel-sub {
  font-size: 12px;
  color: #8A97A8;
}
.day-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.day-item {
  border: 1px solid #E8ECF1;
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 120ms ease-out, background-color 120ms ease-out;
}
.day-item:hover {
  border-color: #0E7C86;
  background: #FFFFFF;
}
.di-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}
.di-no {
  color: #8A97A8;
}
.di-priority {
  font-size: 12px;
}
.di-title {
  font-size: 13px;
  color: #1F2D3D;
  margin: 4px 0 6px;
  line-height: 1.5;
}
.di-title.closed {
  color: #8A97A8;
  text-decoration: line-through;
}
.di-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #5E6D82;
}
.day-empty {
  font-size: 13px;
  color: #8A97A8;
  padding: 32px 0;
  text-align: center;
}
</style>
