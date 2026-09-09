<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { fetchStatsOverviewApi, type StatsOverview, type StatsRange } from '../../api/stats'
import { resolveApiError } from '../../api/auth'
import { taskStatusMeta } from '../../api/task'
import { useUserStore } from '../../stores/user'

/**
 * 统计总览页（M5，PRD 4.3 / 设计稿 docs/ui/统计总览页.html）。
 * - 权限点 viewStats：前端无权限直接展示占位；后端兜底 3001 同样切占位
 * - 区间：历史累计 / 本月 / 本周 / 自定义，切换即刷新，展示 rangeLabel 口径文案
 * - 6 个 KPI 卡片 + 4 张 ECharts（趋势双折线 / 状态环形 / 优先级横向条形 / 人员负载横向条形）
 * - 图表 resize 自适应（ResizeObserver）；各图独立空数据态
 * 接口：GET /stats/api/v1/overview（#47 未就绪期间走 api/stats.ts 内 mock）
 */

// ---------- 设计稿配色 ----------
const BRAND = '#0E7C86'
const DONE_GREEN = '#2F9E6E'
const GRID_LINE = '#E8ECF1'
const AXIS_TEXT = '#8A97A8'
const TRACK_BG = '#E8ECF1'
/** 优先级条形配色（对齐设计稿）：P0 砖红 / P1 琥珀 / P2 青碧 / P3 辅助灰 */
const PRIORITY_COLORS: Record<string, string> = {
  P0: '#C8493F',
  P1: '#D9822B',
  P2: '#0E7C86',
  P3: '#8A97A8',
}
const PRIORITY_LABELS: Record<string, string> = {
  P0: 'P0 紧急',
  P1: 'P1 高',
  P2: 'P2 中',
  P3: 'P3 低',
}
const PRIORITY_ORDER = ['P0', 'P1', 'P2', 'P3']

// ---------- 权限 ----------
const userStore = useUserStore()
/** 后端返回 3001 时也视为无权限（PRD 4.3：无权限展示占位提示） */
const denied = shallowRef(false)
const hasViewPerm = computed(() => userStore.hasPerm('viewStats') && !denied.value)

// ---------- 区间切换 ----------
const RANGE_OPTIONS: { value: StatsRange; label: string }[] = [
  { value: 'all', label: '历史累计' },
  { value: 'month', label: '本月' },
  { value: 'week', label: '本周' },
  { value: 'custom', label: '自定义' },
]
const rangeType = shallowRef<StatsRange>('all')
const customRange = shallowRef<[string, string] | null>(null)

function switchRange(value: StatsRange) {
  if (rangeType.value === value) return
  rangeType.value = value
  if (value !== 'custom') {
    loadOverview()
  } else if (customRange.value?.[0] && customRange.value?.[1]) {
    loadOverview()
  }
}

function onCustomRangeChange() {
  if (customRange.value?.[0] && customRange.value?.[1]) {
    loadOverview()
  }
}

// ---------- 数据加载 ----------
const loading = shallowRef(false)
const overview = shallowRef<StatsOverview | null>(null)

async function loadOverview() {
  loading.value = true
  try {
    const query =
      rangeType.value === 'custom' && customRange.value
        ? { range: rangeType.value, start: customRange.value[0], end: customRange.value[1] }
        : { range: rangeType.value }
    overview.value = await fetchStatsOverviewApi(query)
    denied.value = false
  } catch (error) {
    const info = resolveApiError(error)
    if (info.code === 3001) {
      // 无 viewStats 权限：切占位，不弹错误
      denied.value = true
    } else {
      ElMessage.error(info.message)
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (hasViewPerm.value) loadOverview()
})

// ---------- KPI ----------
const kpi = computed(() => overview.value?.kpi ?? null)

const avgHoursText = computed(() =>
  kpi.value ? kpi.value.avgCompleteHours.toFixed(1) : '0.0',
)
/** 已逾期占未完成任务百分比（附注，1 位小数） */
const overduePercentText = computed(() => {
  if (!kpi.value || kpi.value.unfinished === 0) return '0.0'
  return ((kpi.value.overdue / kpi.value.unfinished) * 100).toFixed(1)
})
/** 按时完成数（后端只给比率，由 done × onTimeRate 回推整数） */
const onTimeDone = computed(() =>
  kpi.value ? Math.round((kpi.value.done * kpi.value.onTimeRate) / 100) : 0,
)
const p0PercentText = computed(() => (kpi.value ? kpi.value.p0Percent.toFixed(1) : '0.0'))

// ---------- 图表 ----------
const trendEl = shallowRef<HTMLElement | null>(null)
const statusEl = shallowRef<HTMLElement | null>(null)
const priorityEl = shallowRef<HTMLElement | null>(null)
const loadEl = shallowRef<HTMLElement | null>(null)

const charts: echarts.ECharts[] = []
const observers: ResizeObserver[] = []

/** 初始化图表 + ResizeObserver 自适应 */
function initChart(el: HTMLElement | null): echarts.ECharts | null {
  if (!el) return null
  const chart = echarts.init(el)
  const ro = new ResizeObserver(() => chart.resize())
  ro.observe(el)
  charts.push(chart)
  observers.push(ro)
  return chart
}

let trendChart: echarts.ECharts | null = null
let statusChart: echarts.ECharts | null = null
let priorityChart: echarts.ECharts | null = null
let loadChart: echarts.ECharts | null = null

onMounted(() => {
  trendChart = initChart(trendEl.value)
  statusChart = initChart(statusEl.value)
  priorityChart = initChart(priorityEl.value)
  loadChart = initChart(loadEl.value)
})

onBeforeUnmount(() => {
  observers.forEach((ro) => ro.disconnect())
  charts.forEach((c) => c.dispose())
})

/** 数据变化 → 重渲 4 张图 */
watch(overview, (data) => {
  if (!data) return
  renderTrend(data)
  renderStatus(data)
  renderPriority(data)
  renderAssigneeLoad(data)
})

// a) 任务趋势：双折线（新建 青碧 / 完成 松绿），白心圆点；>70 天后端已按周聚合，直接渲染
function renderTrend(data: StatsOverview) {
  trendChart?.setOption(
    {
      grid: { left: 44, right: 16, top: 16, bottom: 28 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: data.trend.map((p) => p.date.slice(5)),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: GRID_LINE } },
        axisLabel: { color: AXIS_TEXT, fontSize: 11 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: GRID_LINE } },
        axisLabel: { color: AXIS_TEXT, fontSize: 11 },
      },
      series: [
        {
          name: '新建',
          type: 'line',
          data: data.trend.map((p) => p.created),
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2, color: BRAND },
          itemStyle: { color: '#FFFFFF', borderColor: BRAND, borderWidth: 2 },
        },
        {
          name: '完成',
          type: 'line',
          data: data.trend.map((p) => p.completed),
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2, color: DONE_GREEN },
          itemStyle: { color: '#FFFFFF', borderColor: DONE_GREEN, borderWidth: 2 },
        },
      ],
    },
    true,
  )
}

// b) 状态分布：环形图，中心显示总数；右侧 HTML 图例（数量 + 占比）
const statusLegend = computed(() => {
  const list = overview.value?.statusDistribution ?? []
  const total = list.reduce((sum, s) => sum + s.count, 0)
  return list.map((s) => {
    const meta = taskStatusMeta(s.status)
    return {
      status: s.status,
      name: meta.name,
      color: meta.color,
      count: s.count,
      pct: total > 0 ? ((s.count / total) * 100).toFixed(1) : '0.0',
    }
  })
})

function renderStatus(data: StatsOverview) {
  const total = data.statusDistribution.reduce((sum, s) => sum + s.count, 0)
  statusChart?.setOption(
    {
      tooltip: { trigger: 'item' },
      title: {
        text: String(total),
        subtext: '总数',
        left: 'center',
        top: '34%',
        textStyle: { fontSize: 30, fontWeight: 600, color: '#1F2D3D' },
        subtextStyle: { fontSize: 12, color: AXIS_TEXT },
        itemGap: 2,
      },
      series: [
        {
          type: 'pie',
          radius: ['62%', '84%'],
          center: ['50%', '50%'],
          label: { show: false },
          itemStyle: { borderColor: '#FFFFFF', borderWidth: 2 },
          data: data.statusDistribution.map((s) => {
            const meta = taskStatusMeta(s.status)
            return { name: meta.name, value: s.count, itemStyle: { color: meta.color } }
          }),
        },
      ],
    },
    true,
  )
}

// c) 优先级分布：横向条形（P0→P3 自上而下，设计稿配色）
function renderPriority(data: StatsOverview) {
  const countOf = new Map(data.priorityDistribution.map((p) => [p.priority, p.count]))
  const extra = data.priorityDistribution
    .map((p) => p.priority)
    .filter((p) => !PRIORITY_ORDER.includes(p))
  const order = [...PRIORITY_ORDER, ...extra]
  priorityChart?.setOption(
    {
      grid: { left: 70, right: 40, top: 10, bottom: 26 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: GRID_LINE } },
        axisLabel: { color: AXIS_TEXT, fontSize: 11 },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: order.map((p) => PRIORITY_LABELS[p] ?? p),
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: { color: '#5E6D82', fontSize: 13 },
      },
      series: [
        {
          type: 'bar',
          barWidth: 20,
          showBackground: true,
          backgroundStyle: { color: TRACK_BG, borderRadius: 4 },
          itemStyle: { borderRadius: 4 },
          label: { show: true, position: 'right', color: '#1F2D3D', fontWeight: 500 },
          data: order.map((p) => ({
            value: countOf.get(p) ?? 0,
            itemStyle: { color: PRIORITY_COLORS[p] ?? BRAND },
          })),
        },
      ],
    },
    true,
  )
}

// d) 人员负载：横向条形，按未完成数降序（后端已排序，inverse 轴保证最大在上）
function renderAssigneeLoad(data: StatsOverview) {
  const sorted = [...data.assigneeLoad].sort((a, b) => b.unfinished - a.unfinished)
  loadChart?.setOption(
    {
      grid: { left: 70, right: 40, top: 10, bottom: 26 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: GRID_LINE } },
        axisLabel: { color: AXIS_TEXT, fontSize: 11 },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: sorted.map((a) => a.assigneeName),
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: { color: '#5E6D82', fontSize: 13 },
      },
      series: [
        {
          type: 'bar',
          barWidth: 20,
          showBackground: true,
          backgroundStyle: { color: TRACK_BG, borderRadius: 4 },
          itemStyle: { color: BRAND, borderRadius: 4 },
          label: { show: true, position: 'right', color: '#1F2D3D', fontWeight: 500 },
          data: sorted.map((a) => a.unfinished),
        },
      ],
    },
    true,
  )
}

// ---------- 空数据态 ----------
const trendEmpty = computed(() => (overview.value?.trend.length ?? 0) === 0)
const statusEmpty = computed(
  () => statusLegend.value.reduce((sum, s) => sum + s.count, 0) === 0,
)
const priorityEmpty = computed(
  () => (overview.value?.priorityDistribution ?? []).every((p) => p.count === 0),
)
const loadEmpty = computed(() => (overview.value?.assigneeLoad.length ?? 0) === 0)
</script>

<template>
  <div class="stats-view">
    <!-- 无权限占位（前端无 viewStats 或后端 3001） -->
    <div v-if="!hasViewPerm" class="no-perm tf-card">
      <el-empty description="暂无统计总览访问权限，请联系管理员开通 viewStats 权限" />
    </div>

    <template v-else>
      <!-- 页面标题行 + 区间切换 -->
      <div class="page-head">
        <h1 class="page-title">统计总览</h1>
        <div class="range-ctl">
          <el-date-picker
            v-if="rangeType === 'custom'"
            v-model="customRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            size="default"
            @change="onCustomRangeChange"
          />
          <div class="segment" role="tablist">
            <button
              v-for="opt in RANGE_OPTIONS"
              :key="opt.value"
              type="button"
              :class="{ on: rangeType === opt.value }"
              @click="switchRange(opt.value)"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
      </div>
      <div class="range-note">
        统计区间：{{ overview?.rangeLabel ?? '—' }} · 以任务创建时间为准 · 仅含顶层任务
      </div>

      <div v-loading="loading">
        <!-- KPI 卡片行 -->
        <section class="kpi-row">
          <div class="kpi-card">
            <div class="kpi-name">任务总数</div>
            <div class="kpi-value tf-num">{{ kpi?.total ?? 0 }}</div>
            <div class="kpi-note">已完成 {{ kpi?.done ?? 0 }} · 未完成 {{ kpi?.unfinished ?? 0 }}</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-name">待办 / 进行中</div>
            <div class="kpi-value tf-num">{{ kpi?.unfinished ?? 0 }}</div>
            <div class="kpi-note">待办 {{ kpi?.todo ?? 0 }} · 进行中 {{ kpi?.doing ?? 0 }}</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-name">已逾期</div>
            <div class="kpi-value tf-num danger">{{ kpi?.overdue ?? 0 }}</div>
            <div class="kpi-note">占未完成任务 {{ overduePercentText }}%</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-name">紧急任务（P0）</div>
            <div class="kpi-value tf-num">{{ kpi?.p0 ?? 0 }}</div>
            <div class="kpi-note">占总数 {{ p0PercentText }}%</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-name">平均完成时长</div>
            <div class="kpi-value tf-num">{{ avgHoursText }}<span class="unit">h</span></div>
            <div class="kpi-note">按完成任务的更新时间-创建时间</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-name">按时完成率</div>
            <div class="kpi-value tf-num good">{{ kpi?.onTimeRate ?? 0 }}<span class="unit">%</span></div>
            <div class="kpi-note">按时完成 {{ onTimeDone }} / 已完成 {{ kpi?.done ?? 0 }}</div>
          </div>
        </section>

        <!-- 图表 2×2 栅格 -->
        <section class="chart-grid">
          <!-- a) 任务趋势 -->
          <div class="chart-card">
            <div class="chart-head">
              <span class="chart-title">任务趋势</span>
              <div class="chart-legend">
                <span class="lg"><span class="lg-line"></span>新建</span>
                <span class="lg"><span class="lg-line green"></span>完成</span>
              </div>
            </div>
            <div class="chart-body">
              <div ref="trendEl" class="chart-canvas"></div>
              <el-empty
                v-if="trendEmpty && !loading"
                description="区间内暂无任务数据"
                :image-size="72"
                class="chart-empty"
              />
            </div>
          </div>

          <!-- b) 状态分布 -->
          <div class="chart-card">
            <div class="chart-head">
              <span class="chart-title">状态分布</span>
            </div>
            <div class="chart-body donut-wrap">
              <div ref="statusEl" class="chart-canvas donut-canvas"></div>
              <div v-if="!statusEmpty" class="donut-legend">
                <div v-for="s in statusLegend" :key="s.status" class="dl-item">
                  <span class="dl-dot" :style="{ background: s.color }"></span>
                  <span class="dl-name">{{ s.name }}</span>
                  <span class="dl-count tf-num">{{ s.count }}</span>
                  <span class="dl-pct tf-num">{{ s.pct }}%</span>
                </div>
              </div>
              <el-empty
                v-if="statusEmpty && !loading"
                description="区间内暂无任务数据"
                :image-size="72"
                class="chart-empty"
              />
            </div>
          </div>

          <!-- c) 优先级分布 -->
          <div class="chart-card">
            <div class="chart-head">
              <span class="chart-title">优先级分布</span>
            </div>
            <div class="chart-body">
              <div ref="priorityEl" class="chart-canvas"></div>
              <el-empty
                v-if="priorityEmpty && !loading"
                description="区间内暂无任务数据"
                :image-size="72"
                class="chart-empty"
              />
            </div>
            <div class="chart-note">按优先级统计全部 {{ kpi?.total ?? 0 }} 个顶层任务</div>
          </div>

          <!-- d) 人员负载 -->
          <div class="chart-card">
            <div class="chart-head">
              <span class="chart-title">人员负载</span>
            </div>
            <div class="chart-body">
              <div ref="loadEl" class="chart-canvas"></div>
              <el-empty
                v-if="loadEmpty && !loading"
                description="区间内暂无未完成任务"
                :image-size="72"
                class="chart-empty"
              />
            </div>
            <div class="chart-note">不含系统管理员</div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.stats-view {
  font-variant-numeric: tabular-nums;
}

/* 页面标题行 */
.page-head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1F2D3D;
  margin: 0;
}
.range-ctl {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
/* 分段切换（设计稿 .segment） */
.segment {
  display: flex;
  background: #E8ECF1;
  border-radius: 6px;
  padding: 2px;
}
.segment button {
  border: none;
  background: transparent;
  height: 28px;
  padding: 0 14px;
  border-radius: 4px;
  font-size: 13px;
  font-family: inherit;
  color: #5E6D82;
  cursor: pointer;
}
.segment button.on {
  background: #0E7C86;
  color: #FFFFFF;
  font-weight: 500;
}
.range-note {
  margin-top: 8px;
  font-size: 12px;
  color: #8A97A8;
}

/* KPI 卡片（设计稿 .kpi-row/.kpi-card） */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  margin-top: 20px;
}
.kpi-card {
  background: #FFFFFF;
  border: 1px solid #D8DEE6;
  border-radius: 8px;
  padding: 16px;
  min-height: 108px;
  display: flex;
  flex-direction: column;
}
.kpi-name {
  font-size: 12px;
  color: #5E6D82;
}
.kpi-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
  line-height: 34px;
  color: #1F2D3D;
}
.kpi-value .unit {
  font-size: 14px;
  font-weight: 500;
  margin-left: 2px;
}
.kpi-value.danger {
  color: #C8493F;
}
.kpi-value.good {
  color: #2F9E6E;
}
.kpi-note {
  margin-top: auto;
  padding-top: 8px;
  font-size: 12px;
  color: #8A97A8;
  line-height: 18px;
}

/* 图表栅格（设计稿 .chart-grid/.chart-card） */
.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 16px;
}
.chart-card {
  background: #FFFFFF;
  border: 1px solid #D8DEE6;
  border-radius: 8px;
  padding: 20px;
}
.chart-head {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}
.chart-title {
  font-size: 16px;
  font-weight: 600;
  color: #1F2D3D;
}
.chart-legend {
  margin-left: auto;
  display: flex;
  gap: 16px;
}
.chart-legend .lg {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #5E6D82;
}
.lg-line {
  width: 16px;
  height: 0;
  border-top: 2px solid #0E7C86;
  position: relative;
}
.lg-line::after {
  content: "";
  position: absolute;
  left: 50%;
  top: 50%;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #0E7C86;
  transform: translate(-50%, -50%);
}
.lg-line.green {
  border-top-color: #2F9E6E;
}
.lg-line.green::after {
  background: #2F9E6E;
}

.chart-body {
  position: relative;
  display: flex;
  align-items: center;
}
.chart-canvas {
  width: 100%;
  height: 240px;
}
.donut-canvas {
  flex: 0 0 240px;
  width: 240px;
}
.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.85);
}

/* 环形图右侧图例（设计稿 .donut-legend） */
.donut-legend {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.dl-item {
  display: flex;
  align-items: center;
  font-size: 13px;
}
.dl-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
  margin-right: 8px;
}
.dl-name {
  color: #1F2D3D;
  width: 56px;
}
.dl-count {
  color: #5E6D82;
  margin-left: auto;
}
.dl-pct {
  color: #8A97A8;
  width: 52px;
  text-align: right;
}
.chart-note {
  margin-top: 14px;
  font-size: 12px;
  color: #8A97A8;
}

.no-perm {
  padding: 64px 24px;
}
</style>
