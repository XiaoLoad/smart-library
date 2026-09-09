<template>
  <div class="page dashboard">
    <!-- 页面头部 -->
    <header class="page-header">
      <span class="eyebrow">概览 · OVERVIEW</span>
      <h1>{{ pageTitle }}</h1>
      <p class="desc">{{ greeting }}，{{ userStore.user?.realName }}。{{ pageDesc }}</p>
    </header>

    <!-- 统计卡片 -->
    <section class="stats-grid fade-in" v-if="data.stats.length">
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
          {{ stat.value.toLocaleString() }}<span class="stat-unit">{{ stat.unit }}</span>
        </div>
        <div class="stat-trend" :class="{ danger: stat.danger, success: stat.trend.includes('+') }">
          {{ stat.trend }}
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
            <span class="eyebrow">分布 · CATEGORY</span>
            <h3 class="chart-title">{{ distTitle }}</h3>
          </div>
        </div>
        <v-chart :option="categoryPieOption" class="chart-canvas" autoresize />
      </div>
    </section>

    <!-- 热门图书 + 公告 -->
    <section class="bottom-row fade-in" v-if="data.stats.length">
      <div class="card hot-books-card">
        <div class="chart-header">
          <div>
            <span class="eyebrow">{{ userStore.isAdmin ? '热门 · POPULAR' : '推荐 · RECOMMEND' }}</span>
            <h3 class="chart-title">{{ userStore.isAdmin ? '借阅排行' : '推荐阅读' }}</h3>
          </div>
        </div>
        <ul class="hot-list">
          <li v-for="book in data.hotBooks" :key="book.rank" class="hot-item">
            <div class="rank" :class="`rank-${book.rank}`">{{ book.rank }}</div>
            <div class="book-meta">
              <div class="book-title">{{ book.title }}</div>
              <div class="book-author">{{ book.author }}</div>
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
import { LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  GraphicComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'
import {
  Reading, User, Tickets, Warning, DataAnalysis,
} from '@element-plus/icons-vue'
import { dashboardApi, announcementApi } from '@/api'
import { useUserStore } from '@/stores/user'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, GraphicComponent])

const userStore = useUserStore()
const data = ref({ stats: [], borrowTrend: {}, hotBooks: [], categoryDist: [] })
const notices = ref([])

const iconMap = { Reading, User, Tickets, Warning, DataAnalysis }

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
const pageDesc = computed(() => userStore.isAdmin ? '这是您馆藏的实时状态。' : '这是您的借阅与阅读概况。')
const trendTitle = computed(() => userStore.isAdmin ? '近六月借阅流转' : '近六月我的借阅')
const distTitle = computed(() => userStore.isAdmin ? '借阅分类（按借阅次数）' : '我的阅读分类')

const borrowTrendOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(255,255,255,0.96)',
    borderColor: '#e8e3dc',
    textStyle: { color: '#2c2825', fontSize: 12 },
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

const categoryPieOption = computed(() => {
  const dist = data.value.categoryDist || []
  const total = dist.reduce((s, c) => s + c.value, 0)
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e8e3dc',
      textStyle: { color: '#2c2825', fontSize: 12 },
      formatter: '{b}: {c} ({d}%)',
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
    graphic: {
      type: 'text',
      left: 'center',
      top: '42%',
      style: {
        text: '合计',
        fontSize: 12,
        fill: '#9c958d',
        textAlign: 'center',
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['48%', '70%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: {
          show: true,
          position: 'outside',
          formatter: '{b}\n{d}%',
          fontSize: 11,
          color: '#6b6560',
          lineHeight: 16,
        },
        labelLine: {
          show: true,
          length: 8,
          length2: 8,
          smooth: true,
        },
        data: dist.map((c, i) => ({
          name: c.name,
          value: c.value,
          itemStyle: {
            color: ['#3a5a6b', '#7a9270', '#c4904a', '#a85a4a'][i % 4],
          },
        })),
      },
      // 中心总计文字层（不可见扇区）
      {
        type: 'pie',
        radius: ['0%', '0%'],
        silent: true,
        label: {
          show: true,
          position: 'center',
          formatter: `{c|${total}}\n{a|册}`,
          rich: {
            c: { fontSize: 22, fontWeight: 600, color: '#2c2825', fontFamily: 'Noto Serif SC', lineHeight: 28 },
            a: { fontSize: 11, color: '#9c958d' },
          },
        },
        labelLine: { show: false },
        data: [{ value: total }],
      },
    ],
  }
})

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

.stat-icon.danger {
  background: rgba(168, 90, 74, 0.12);
  color: var(--color-danger);
}

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.chart-card {
  padding: var(--space-5);
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
  gap: var(--space-3);
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
}

.book-count {
  text-align: right;
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

@media (max-width: 1024px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: 1fr; }
  .bottom-row { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .chart-canvas { height: 220px; }
  .stats-grid { gap: var(--space-3); }
  .stat-card { padding: var(--space-4); }
  .stat-card .stat-value { font-size: 1.75rem; }
}

@media (max-width: 480px) {
  .stats-grid { grid-template-columns: 1fr; }
  .chart-canvas { height: 200px; }
  .chart-card { padding: var(--space-4); }
}
</style>
