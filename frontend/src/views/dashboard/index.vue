<template>
  <div class="page dashboard">
    <!-- 页面头部 -->
    <header class="page-header">
      <span class="eyebrow">概览 · OVERVIEW</span>
      <h1>{{ pageTitle }}</h1>
      <p class="desc">{{ greeting }}，{{ userStore.user?.realName }}。{{ pageDesc }}</p>
    </header>

    <!-- 统计卡片 -->
    <section class="stats-grid fade-in" :class="{ 'is-admin': userStore.isAdmin }" v-if="data.stats.length">
      <div
        v-for="stat in data.stats"
        :key="stat.key"
        class="card stat-card card-hover"
      >
        <div class="stat-icon" :class="{ danger: stat.danger }">
          <el-icon><component :is="iconMap[stat.icon]" /></el-icon>
        </div>
        <div class="stat-label">{{ stat.label }}</div>
        <div class="stat-value">
          {{ Number(stat.value).toLocaleString() }}<span class="stat-unit">{{ stat.unit }}</span>
        </div>
        <div class="stat-trend" :class="{ danger: stat.danger, success: stat.trend.includes('+') || stat.trend.includes('共') || stat.trend.includes('注册') }">
          {{ stat.trend }}
        </div>
      </div>
    </section>

    <!-- 管理员待办条 -->
    <section class="todo-row fade-in" v-if="userStore.isAdmin && ops">
      <router-link to="/borrow" class="card todo-card card-hover todo-pickup" :class="{ empty: !ops.pendingPickup }">
        <div class="todo-mark"><el-icon><Box /></el-icon></div>
        <div class="todo-body">
          <div class="todo-num">{{ ops.pendingPickup }}<span class="todo-num-unit">单</span></div>
          <div class="todo-label">待取书预约</div>
          <div class="todo-sub">读者已申请，等待到馆取书</div>
        </div>
        <div class="todo-go">办理<el-icon><ArrowRight /></el-icon></div>
      </router-link>

      <router-link to="/borrow" class="card todo-card card-hover todo-return" :class="{ empty: !ops.pendingReturnConfirm }">
        <div class="todo-mark"><el-icon><CircleCheckFilled /></el-icon></div>
        <div class="todo-body">
          <div class="todo-num">{{ ops.pendingReturnConfirm }}<span class="todo-num-unit">单</span></div>
          <div class="todo-label">归还待确认</div>
          <div class="todo-sub">读者已还书，等待柜台确认</div>
        </div>
        <div class="todo-go">办理<el-icon><ArrowRight /></el-icon></div>
      </router-link>

      <router-link to="/borrow" class="card todo-card card-hover todo-overdue" :class="{ empty: !ops.overdue }">
        <div class="todo-mark"><el-icon><WarningFilled /></el-icon></div>
        <div class="todo-body">
          <div class="todo-num">{{ ops.overdue }}<span class="todo-num-unit">本</span></div>
          <div class="todo-label">逾期未还</div>
          <div class="todo-sub">超期图书，需要催还处理</div>
        </div>
        <div class="todo-go">催还<el-icon><ArrowRight /></el-icon></div>
      </router-link>

      <div class="card todo-card todo-mini">
        <div class="mini-item">
          <span class="mini-num">{{ ops.activeReaders30d ?? '—' }}</span>
          <span class="mini-label">近30天<br />活跃读者</span>
        </div>
        <div class="mini-divider"></div>
        <div class="mini-item">
          <span class="mini-num">{{ ops.monthNewBooks ?? '—' }}</span>
          <span class="mini-label">近30天<br />新入藏图书</span>
        </div>
      </div>
    </section>

    <!-- 图表行 -->
    <section class="chart-row fade-in" v-if="data.stats.length">
      <div class="card chart-card chart-main">
        <div class="chart-header">
          <div>
            <span class="eyebrow">趋势 · TREND</span>
            <h3 class="chart-title">{{ trendTitle }}</h3>
          </div>
          <div class="chart-legend">
            <span class="legend-item"><i class="dot primary"></i>借出</span>
            <span class="legend-item"><i class="dot secondary"></i>归还</span>
          </div>
        </div>
        <v-chart :option="borrowTrendOption" class="chart-canvas" autoresize />
      </div>

      <div class="card chart-card chart-side">
        <div class="chart-header">
          <div>
            <span class="eyebrow">{{ userStore.isAdmin ? '在办 · ACTIVE' : '分布 · CATEGORY' }}</span>
            <h3 class="chart-title">{{ distTitle }}</h3>
          </div>
        </div>
        <v-chart :option="sidePieOption" class="chart-canvas" autoresize />
      </div>
    </section>

    <!-- 管理员：藏书分类条形图行 -->
    <section class="chart-row fade-in" v-if="userStore.isAdmin && data.stats.length">
      <div class="card chart-card chart-collection">
        <div class="chart-header">
          <div>
            <span class="eyebrow">馆藏 · COLLECTION</span>
            <h3 class="chart-title">分类藏书分布</h3>
          </div>
          <div class="chart-legend">
            <span class="legend-item"><i class="dot gold"></i>总册数</span>
            <span class="legend-item"><i class="dot primary-light"></i>可借册数</span>
          </div>
        </div>
        <v-chart :option="collectionBarOption" class="chart-canvas collection-canvas" autoresize />
      </div>
    </section>

    <!-- 热门图书 + 公告 -->
    <section class="bottom-row fade-in" :class="{ 'is-admin': userStore.isAdmin }" v-if="data.stats.length">
      <div class="card hot-books-card">
        <div class="chart-header">
          <div>
            <span class="eyebrow">{{ userStore.isAdmin ? '热门 · POPULAR' : '推荐 · RECOMMEND' }}</span>
            <h3 class="chart-title">{{ userStore.isAdmin ? '借阅排行' : '推荐阅读' }}</h3>
          </div>
          <span class="chart-note" v-if="userStore.isAdmin">按实际取书次数</span>
        </div>
        <ul class="hot-list">
          <li v-for="book in data.hotBooks" :key="book.rank" class="hot-item">
            <div class="rank" :class="`rank-${book.rank}`">{{ book.rank }}</div>
            <div class="book-meta">
              <div class="book-title">{{ book.title }}</div>
              <div class="book-author">{{ book.author }}</div>
              <div class="hot-bar">
                <span
                  class="hot-bar-fill"
                  :class="{ top: book.rank <= 3 }"
                  :style="{ width: hotBarWidth(book.count) + '%' }"
                ></span>
              </div>
            </div>
            <div class="book-count">
              <span class="count-num">{{ book.count }}</span>
              <span class="count-unit">次借阅</span>
            </div>
          </li>
        </ul>
      </div>

      <div class="card notice-card">
        <div class="chart-header">
          <div>
            <span class="eyebrow">公告 · NOTICE</span>
            <h3 class="chart-title">最新通知</h3>
          </div>
          <router-link to="/announce/list" class="more-link">全部</router-link>
        </div>
        <ul class="notice-list">
          <li v-for="notice in notices" :key="notice.id" class="notice-item">
            <div class="notice-dot" :class="{ top: notice.top }"></div>
            <div class="notice-content">
              <div class="notice-title">{{ notice.title }}</div>
              <div class="notice-time">{{ formatTime(notice.publishTime) }}</div>
            </div>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  GraphicComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import {
  Reading, User, Tickets, Warning, DataAnalysis, Coin,
  Box, CircleCheckFilled, WarningFilled, ArrowRight,
} from '@element-plus/icons-vue'
import { dashboardApi, announcementApi } from '@/api'
import { useUserStore } from '@/stores/user'

use([CanvasRenderer, LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, GraphicComponent])

const userStore = useUserStore()
const data = ref({ stats: [], borrowTrend: {}, hotBooks: [], categoryDist: [], categoryBookDist: [], operations: {} })
const notices = ref([])

const ops = computed(() => data.value.operations || {})

const iconMap = { Reading, User, Tickets, Warning, DataAnalysis, Coin }

// 在办状态配色（环图）
const STATUS_COLORS = {
  待取书: '#c4904a',
  借阅中: '#3a5a6b',
  已逾期: '#a85a4a',
  待确认归还: '#7a9270',
}
const CATE_COLORS = ['#3a5a6b', '#7a9270', '#c4904a', '#a85a4a', '#8a7ca8', '#5b8a96', '#b07a52']

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

// 角色差异化文案：管理员=全局视角，教师/学生=个人借阅视角
const pageTitle = computed(() => userStore.isAdmin ? '今日图书馆' : '我的图书馆')
const pageDesc = computed(() => userStore.isAdmin ? '这是馆藏的实时状态与今日待办。' : '这是您的借阅与阅读概况。')
const trendTitle = computed(() => userStore.isAdmin ? '近六月借阅流转' : '近六月我的借阅')
const distTitle = computed(() => userStore.isAdmin ? '在办事务状态' : '我的阅读分类')

const baseTooltip = {
  backgroundColor: 'rgba(255,255,255,0.96)',
  borderColor: '#e8e3dc',
  textStyle: { color: '#2c2825', fontSize: 12 },
}

const borrowTrendOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    ...baseTooltip,
    axisPointer: { type: 'line', lineStyle: { color: '#e8e3dc', type: 'dashed' } },
  },
  grid: { left: 40, right: 20, top: 20, bottom: 30, containLabel: true },
  xAxis: {
    type: 'category',
    data: data.value.borrowTrend.months || [],
    axisLine: { lineStyle: { color: '#e8e3dc' } },
    axisTick: { show: false },
    axisLabel: { color: '#9c958d', fontSize: 11 },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: '#ece6dd', type: 'dashed' } },
    axisLabel: { color: '#9c958d', fontSize: 11 },
  },
  series: [
    {
      name: '借出',
      type: 'line',
      data: data.value.borrowTrend.borrow || [],
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#3a5a6b', width: 2 },
      itemStyle: { color: '#3a5a6b' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(58, 90, 107, 0.18)' },
            { offset: 1, color: 'rgba(58, 90, 107, 0)' },
          ],
        },
      },
    },
    {
      name: '归还',
      type: 'line',
      data: data.value.borrowTrend.return || [],
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#7a9270', width: 2, type: 'dashed' },
      itemStyle: { color: '#7a9270' },
    },
  ],
}))

// 侧栏环图：管理员=在办事务状态，读者=阅读分类
const sidePieOption = computed(() => {
  const isAdmin = userStore.isAdmin
  const dist = isAdmin ? (ops.value.activeStatusDist || []) : (data.value.categoryDist || [])
  const total = dist.reduce((s, c) => s + (c.value || 0), 0)
  const centerText = isAdmin ? '在办' : '合计'
  const centerUnit = isAdmin ? '单' : '次'
  return {
    tooltip: {
      trigger: 'item',
      ...baseTooltip,
      formatter: '{b}: {c} ' + (isAdmin ? '单' : '次') + '（{d}%）',
    },
    legend: {
      show: true,
      orient: 'horizontal',
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { fontSize: 11, color: '#9c958d' },
    },
    series: [
      {
        type: 'pie',
        radius: ['52%', '72%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        data: dist.map((c, i) => ({
          name: c.name,
          value: c.value,
          itemStyle: { color: isAdmin ? STATUS_COLORS[c.name] : CATE_COLORS[i % CATE_COLORS.length] },
        })),
        emphasis: {
          scaleSize: 6,
          label: { show: false },
        },
      },
      // 中心总计文字层（不可见扇区）
      {
        type: 'pie',
        radius: ['0%', '0%'],
        silent: true,
        label: {
          show: true,
          position: 'center',
          formatter: `{c|${total}}\n{a|${centerText}·${centerUnit}}`,
          rich: {
            c: { fontSize: 26, fontWeight: 600, color: '#2c2825', fontFamily: 'Songti SC, STSong, SimSun, serif', lineHeight: 32 },
            a: { fontSize: 11, color: '#9c958d' },
          },
        },
        labelLine: { show: false },
        data: [{ value: total }],
      },
    ],
  }
})

// 分类藏书分布：横向双条形（总册数 / 可借册数）
const collectionBarOption = computed(() => {
  const dist = [...(data.value.categoryBookDist || [])].sort((a, b) => a.copies - b.copies)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(232,227,220,0.4)' } },
      ...baseTooltip,
      formatter: (params) => {
        const p = params[0]
        const item = dist[p.dataIndex]
        return `${item.name}<br/>总册数：<b>${item.copies}</b> 册<br/>可借：<b>${item.availableCopies}</b> 册 · 共 ${item.titles} 种`
      },
    },
    legend: { show: false },
    grid: { left: 10, right: 40, top: 10, bottom: 10, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#ece6dd', type: 'dashed' } },
      axisLabel: { color: '#9c958d', fontSize: 11 },
    },
    yAxis: {
      type: 'category',
      data: dist.map((c) => c.name),
      axisLine: { lineStyle: { color: '#e8e3dc' } },
      axisTick: { show: false },
      axisLabel: { color: '#6b6560', fontSize: 12 },
    },
    series: [
      {
        name: '总册数',
        type: 'bar',
        data: dist.map((c) => c.copies),
        barWidth: 10,
        barGap: '40%',
        itemStyle: { color: '#c4904a', borderRadius: [0, 5, 5, 0] },
        label: { show: true, position: 'right', color: '#9c958d', fontSize: 11 },
      },
      {
        name: '可借册数',
        type: 'bar',
        data: dist.map((c) => c.availableCopies),
        barWidth: 10,
        itemStyle: { color: 'rgba(58,90,107,0.55)', borderRadius: [0, 5, 5, 0] },
      },
    ],
  }
})

const hotMaxCount = computed(() => {
  const list = data.value.hotBooks || []
  return list.length ? Math.max(...list.map((b) => b.count)) : 1
})
const hotBarWidth = (count) => Math.max(8, Math.round((count / hotMaxCount.value) * 100))

const formatTime = (t) => {
  if (!t) return ''
  return t.substring(5, 10)
}

const loadData = async () => {
  const [stats, noticeData] = await Promise.all([
    dashboardApi.stats(userStore.userType),
    announcementApi.list(),
  ])
  data.value = stats
  notices.value = (noticeData.list || []).slice(0, 4)
}

onMounted(loadData)
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.stats-grid.is-admin {
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-3);
}

.stats-grid.is-admin .stat-card {
  padding: var(--space-4);
}

.stats-grid.is-admin .stat-value {
  font-size: 1.6rem;
}

.stat-icon.danger {
  background: rgba(168, 90, 74, 0.12);
  color: var(--color-danger);
}

/* —— 待办印章卡 —— */
.todo-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1.2fr;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.todo-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  overflow: hidden;
  text-decoration: none;
  color: inherit;
  transition: transform 0.25s ease;
}

.todo-card.todo-pickup { border-left: 3px solid #3a5a6b; }
.todo-card.todo-return { border-left: 3px solid #c4904a; }
.todo-card.todo-overdue { border-left: 3px solid #a85a4a; }

.todo-card.empty { opacity: 0.55; }

.todo-mark {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
  background: rgba(58, 90, 107, 0.1);
  color: #3a5a6b;
}

.todo-return .todo-mark { background: rgba(196, 144, 74, 0.14); color: #c4904a; }
.todo-overdue .todo-mark { background: rgba(168, 90, 74, 0.12); color: #a85a4a; }

.todo-body { flex: 1; min-width: 0; }

.todo-num {
  font-family: var(--font-serif);
  font-size: 1.9rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--color-text);
}

.todo-num-unit {
  font-size: 0.8rem;
  font-weight: 400;
  color: var(--color-text-tertiary);
  margin-left: 4px;
  font-family: var(--font-sans);
}

.todo-label {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
  margin: 2px 0;
}

.todo-sub {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-go {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
  flex-shrink: 0;
  transition: gap 0.25s ease, color 0.25s ease;
}

.todo-card:not(.empty):hover .todo-go {
  gap: 6px;
  color: var(--color-primary);
}

.todo-mini {
  display: flex;
  align-items: center;
  justify-content: space-around;
  background: var(--color-bg-subtle);
  border: 1px dashed var(--color-border);
}

.mini-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.mini-num {
  font-family: var(--font-serif);
  font-size: 1.7rem;
  font-weight: 700;
  color: var(--color-primary);
  line-height: 1;
}

.mini-label {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.mini-divider {
  width: 1px;
  height: 36px;
  background: var(--color-divider);
}

/* —— 图表 —— */
.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.chart-card {
  padding: var(--space-5);
}

.chart-collection .collection-canvas {
  height: 220px;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: var(--space-4);
}

.chart-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  margin-top: var(--space-1);
}

.chart-note {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.chart-legend {
  display: flex;
  gap: var(--space-4);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.primary { background: var(--color-primary); }
.dot.secondary { background: var(--color-secondary); }
.dot.gold { background: #c4904a; }
.dot.primary-light { background: rgba(58, 90, 107, 0.55); }

.chart-canvas {
  height: 280px;
  width: 100%;
}

.bottom-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}

/* 热门图书 */
.hot-list {
  list-style: none;
  display: flex;
  flex-direction: column;
}

.hot-item {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-divider);
}

.hot-item:last-child { border-bottom: none; }

.rank {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-body);
  background: var(--color-bg-subtle);
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.rank-1 { background: var(--color-primary); color: var(--color-text-inverse); }
.rank-2 { background: var(--color-secondary); color: var(--color-text-inverse); }
.rank-3 { background: var(--color-warning); color: var(--color-text-inverse); }

.book-meta { flex: 1; min-width: 0; }
.book-title {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.book-author {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

/* 排行比例条 */
.hot-bar {
  height: 4px;
  border-radius: 2px;
  background: var(--color-bg-subtle);
  overflow: hidden;
}

.hot-bar-fill {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: rgba(58, 90, 107, 0.28);
  transition: width 0.6s ease;
}

.hot-bar-fill.top {
  background: linear-gradient(90deg, var(--color-primary), rgba(58, 90, 107, 0.55));
}

.book-count {
  text-align: right;
  flex-shrink: 0;
}

.count-num {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-primary);
}

.count-unit {
  display: block;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

/* 公告 */
.more-link {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: var(--ls-wide);
}

.notice-list {
  list-style: none;
}

.notice-item {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-divider);
}

.notice-item:last-child { border-bottom: none; }

.notice-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-border-strong);
  margin-top: 6px;
  flex-shrink: 0;
}

.notice-dot.top {
  background: var(--color-danger);
  box-shadow: 0 0 0 3px rgba(168, 90, 74, 0.15);
}

.notice-content { flex: 1; min-width: 0; }
.notice-title {
  font-size: var(--fs-body);
  color: var(--color-text);
  line-height: var(--lh-snug);
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.notice-time {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

@media (max-width: 1200px) {
  .stats-grid.is-admin { grid-template-columns: repeat(3, 1fr); }
  .todo-row { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 1024px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .stats-grid.is-admin { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: 1fr; }
  .bottom-row { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .chart-canvas { height: 220px; }
  .stats-grid { gap: var(--space-3); }
  .stat-card { padding: var(--space-4); }
  .stat-card .stat-value { font-size: 1.75rem; }
  .todo-row { grid-template-columns: 1fr; }
}

@media (max-width: 480px) {
  .stats-grid { grid-template-columns: 1fr; }
  .chart-canvas { height: 200px; }
  .chart-card { padding: var(--space-4); }
}
</style>
