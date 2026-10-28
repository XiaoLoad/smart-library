<template>
  <div class="page dashboard">
    <!-- 页眉 · 报刊式刊头 -->
    <header class="page-header masthead">
      <div class="masthead-copy">
        <span class="eyebrow">概览 · OVERVIEW</span>
        <h1>{{ pageTitle }}</h1>
        <p class="desc">{{ greeting }}，{{ userStore.user?.realName }}。{{ pageDesc }}</p>
      </div>
      <div class="masthead-meta">
        <span class="meta-date">{{ dateText }}</span>
        <span class="meta-week">{{ weekText }}</span>
      </div>
    </header>

    <!-- 01 · 概览 -->
    <section class="section reveal" v-if="data.stats.length">
      <div class="section-head">
        <span class="section-index">01</span>
        <h2 class="section-title">{{ userStore.isAdmin ? '全馆一览' : '我的借阅' }}</h2>
        <span class="section-rule"></span>
        <span class="section-note">{{ userStore.isAdmin ? '实时口径' : '截至今日' }}</span>
      </div>

      <div class="overview-grid">
        <!-- 主数字 · 书页侧栏 -->
        <article class="card hero-card">
          <div class="hero-rail">
            <span class="rail-text">{{ userStore.isAdmin ? 'COLLECTION' : 'READING' }}</span>
          </div>
          <div class="hero-body">
            <div class="hero-label">{{ hero.label }}</div>
            <div class="hero-value">
              <span class="num">{{ formatNumber(hero.value) }}</span>
              <span class="unit">{{ hero.unit }}</span>
            </div>
            <div class="hero-sub">{{ hero.trend }}</div>

            <div class="hero-meter" v-if="userStore.isAdmin && collectionStats.copies">
              <div class="meter-head">
                <span>可借 {{ formatNumber(collectionStats.available) }} 册</span>
                <span class="meter-rate">{{ availabilityRate }}%</span>
              </div>
              <div class="meter-track"><span class="meter-fill" :style="{ width: availabilityRate + '%' }"></span></div>
              <div class="meter-foot">馆藏共 {{ formatNumber(collectionStats.titles) }} 种 · 在借 {{ formatNumber(collectionStats.onLoan) }} 册</div>
            </div>
          </div>
        </article>

        <!-- 其余指标 · 账簿式列表 -->
        <div class="index-grid">
          <div v-for="stat in restStats" :key="stat.key" class="index-item" :class="{ danger: stat.danger }">
            <span class="index-label">{{ stat.label }}</span>
            <span class="index-value">
              {{ formatNumber(stat.value) }}<i class="index-unit">{{ stat.unit }}</i>
            </span>
            <span class="index-trend">{{ stat.trend }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 02 · 待办（管理员） -->
    <section class="section reveal" v-if="userStore.isAdmin && ops">
      <div class="section-head">
        <span class="section-index">02</span>
        <h2 class="section-title">今日待办</h2>
        <span class="section-rule"></span>
        <span class="section-note">按柜台流程</span>
      </div>

      <div class="card todo-band">
        <router-link
          v-for="item in todoItems"
          :key="item.key"
          to="/borrow"
          class="todo-cell"
          :class="{ empty: !item.value }"
        >
          <span class="todo-label">
            <i class="todo-dot" :style="{ background: item.color }"></i>{{ item.label }}
          </span>
          <span class="todo-num">{{ item.value }}<i>{{ item.unit }}</i></span>
          <span class="todo-hint">{{ item.hint }}</span>
          <span class="todo-go">{{ item.action }}<el-icon><ArrowRight /></el-icon></span>
        </router-link>

        <div class="todo-metrics">
          <div class="metric">
            <span class="metric-num">{{ ops.activeReaders30d ?? '—' }}</span>
            <span class="metric-label">近 30 天<br />活跃读者</span>
          </div>
          <div class="metric">
            <span class="metric-num">{{ ops.monthNewBooks ?? '—' }}</span>
            <span class="metric-label">近 30 天<br />新入藏</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 03 · 流转 -->
    <section class="section reveal" v-if="data.stats.length">
      <div class="section-head">
        <span class="section-index">{{ userStore.isAdmin ? '03' : '02' }}</span>
        <h2 class="section-title">借阅流转</h2>
        <span class="section-rule"></span>
        <span class="section-note">近六个月</span>
      </div>

      <div class="chart-grid">
        <div class="card panel">
          <div class="panel-head">
            <div>
              <span class="panel-eyebrow">TREND</span>
              <h3 class="panel-title">{{ trendTitle }}</h3>
            </div>
            <div class="panel-figures">
              <span class="figure"><i class="dot primary"></i>借出 <b>{{ trendTotals.borrow }}</b></span>
              <span class="figure"><i class="dot secondary"></i>归还 <b>{{ trendTotals.return }}</b></span>
            </div>
          </div>
          <v-chart :option="borrowTrendOption" class="chart-canvas" autoresize />
        </div>

        <div class="card panel">
          <div class="panel-head">
            <div>
              <span class="panel-eyebrow">{{ userStore.isAdmin ? 'ACTIVE' : 'CATEGORY' }}</span>
              <h3 class="panel-title">{{ distTitle }}</h3>
            </div>
          </div>
          <div class="donut-wrap">
            <div class="donut-chart">
              <v-chart :option="sidePieOption" class="donut-canvas" autoresize />
              <div class="donut-center">
                <span class="donut-total">{{ formatNumber(donutTotal) }}</span>
                <span class="donut-caption">{{ donutCaption }}</span>
              </div>
            </div>
            <ul class="donut-legend">
              <li v-for="item in donutData" :key="item.name">
                <i class="legend-dot" :style="{ background: item.color }"></i>
                <span class="legend-name">{{ item.name }}</span>
                <span class="legend-value">{{ item.value }}<i>{{ donutUnit }}</i></span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- 04 · 馆藏结构（管理员） -->
    <section class="section reveal" v-if="userStore.isAdmin && data.stats.length">
      <div class="section-head">
        <span class="section-index">04</span>
        <h2 class="section-title">馆藏结构</h2>
        <span class="section-rule"></span>
        <span class="section-note">按一级分类</span>
      </div>

      <div class="card panel">
        <div class="panel-head">
          <div>
            <span class="panel-eyebrow">COLLECTION</span>
            <h3 class="panel-title">分类藏书分布</h3>
          </div>
          <div class="coll-figures">
            <div class="coll-figure">
              <span class="coll-num">{{ formatNumber(collectionStats.copies) }}</span>
              <span class="coll-label">总册</span>
            </div>
            <div class="coll-figure">
              <span class="coll-num primary">{{ formatNumber(collectionStats.available) }}</span>
              <span class="coll-label">可借</span>
            </div>
            <div class="coll-figure">
              <span class="coll-num warning">{{ formatNumber(collectionStats.onLoan) }}</span>
              <span class="coll-label">在借</span>
            </div>
          </div>
        </div>

        <div class="collection-legend">
          <span class="legend-item"><i class="dot primary"></i>可借册数</span>
          <span class="legend-item"><i class="dot gold"></i>在借册数</span>
          <span class="legend-item hint">横条总长 = 该分类总册数</span>
        </div>

        <v-chart :option="collectionBarOption" class="chart-canvas collection-canvas" autoresize />
      </div>
    </section>

    <!-- 05 · 排行与馆讯 -->
    <section class="section reveal" v-if="data.stats.length">
      <div class="section-head">
        <span class="section-index">{{ userStore.isAdmin ? '05' : '03' }}</span>
        <h2 class="section-title">{{ userStore.isAdmin ? '借阅榜与馆讯' : '我的热门与馆讯' }}</h2>
        <span class="section-rule"></span>
        <span class="section-note">按实际取书次数</span>
      </div>

      <div class="bottom-grid">
        <div class="card panel hot-panel">
          <div class="panel-head">
            <div>
              <span class="panel-eyebrow">POPULAR</span>
              <h3 class="panel-title">借阅热门</h3>
            </div>
            <router-link to="/book/list" class="more-link">馆藏目录</router-link>
          </div>

          <ul class="hot-list" v-if="data.hotBooks.length">
            <li v-for="book in data.hotBooks" :key="book.rank" class="hot-item">
              <span class="rank-no" :class="{ top: book.rank <= 3 }">{{ String(book.rank).padStart(2, '0') }}</span>
              <div class="hot-cover">
                <img
                  v-if="book.coverUrl && !brokenCovers.has(book.bookId)"
                  :src="book.coverUrl"
                  :alt="book.title"
                  loading="lazy"
                  @error="brokenCovers.add(book.bookId)"
                />
                <span v-else class="cover-fallback">{{ book.title?.charAt(0) || '书' }}</span>
              </div>
              <div class="hot-main">
                <EllipsisText class="hot-title" :content="book.title" />
                <div class="hot-sub">
                  <span>{{ book.author || '佚名' }}</span>
                  <span class="hot-sep">·</span>
                  <span>可借 {{ book.availableCount }}/{{ book.totalCount }}</span>
                </div>
                <div class="hot-bar">
                  <span class="hot-bar-fill" :style="{ width: hotBarWidth(book.count) + '%' }"></span>
                </div>
              </div>
              <div class="hot-count">
                <span class="count-num">{{ book.count }}</span>
                <span class="count-unit">次</span>
              </div>
            </li>
          </ul>
          <div v-else class="list-empty">暂无借阅记录</div>
        </div>

        <div class="card panel notice-panel">
          <div class="panel-head">
            <div>
              <span class="panel-eyebrow">NOTICE</span>
              <h3 class="panel-title">馆讯</h3>
            </div>
            <router-link to="/announce/list" class="more-link">全部</router-link>
          </div>

          <ul class="notice-list" v-if="notices.length">
            <li v-for="notice in notices" :key="notice.id" class="notice-item">
              <span class="notice-date">{{ noticeDate(notice.publishTime) }}</span>
              <div class="notice-body">
                <EllipsisText class="notice-title" :content="notice.title" :lines="2" />
              </div>
              <i v-if="notice.top" class="pin-dot" title="置顶"></i>
            </li>
          </ul>
          <div v-else class="list-empty">暂无公告</div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowRight } from '@element-plus/icons-vue'
import { dashboardApi, announcementApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import EllipsisText from '@/components/EllipsisText.vue'

use([CanvasRenderer, LineChart, PieChart, BarChart, GridComponent, TooltipComponent])

const userStore = useUserStore()
const appStore = useAppStore()
const data = ref({ stats: [], borrowTrend: {}, hotBooks: [], categoryDist: [], categoryBookDist: [], collection: {}, operations: {} })
const notices = ref([])
const brokenCovers = reactive(new Set())

const ops = computed(() => data.value.operations || {})
const hero = computed(() => data.value.stats[0] || {})
const restStats = computed(() => data.value.stats.slice(1))

// 在办状态配色（环图）
const STATUS_COLORS = {
  待取书: '#c4904a',
  借阅中: '#3a5a6b',
  已逾期: '#a85a4a',
  待确认归还: '#7a9270',
}
const CATE_COLORS = ['#3a5a6b', '#7a9270', '#c4904a', '#a85a4a', '#8a7ca8', '#5b8a96', '#b07a52']

/* 图表无法直接吃 CSS 变量，按当前主题解析一次色板 */
const palette = computed(() => {
  void appStore.theme
  const css = getComputedStyle(document.documentElement)
  const get = (name, fallback) => (css.getPropertyValue(name) || '').trim() || fallback
  return {
    text: get('--color-text', '#2c2825'),
    textSecondary: get('--color-text-secondary', '#6b6560'),
    tertiary: get('--color-text-tertiary', '#9c958d'),
    border: get('--color-border', '#e8e3dc'),
    divider: get('--color-divider', '#ece6dd'),
    elevated: get('--color-bg-elevated', '#ffffff'),
    primary: get('--color-primary', '#3a5a6b'),
    secondary: get('--color-secondary', '#7a9270'),
    warning: get('--color-warning', '#c4904a'),
    danger: get('--color-danger', '#a85a4a'),
  }
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const now = new Date()
const dateText = `${now.getFullYear()}.${String(now.getMonth() + 1).padStart(2, '0')}.${String(now.getDate()).padStart(2, '0')}`
const weekText = '星期' + '日一二三四五六'[now.getDay()]

// 角色差异化文案：管理员=全局视角，教师/学生=个人借阅视角
const pageTitle = computed(() => userStore.isAdmin ? '今日图书馆' : '我的图书馆')
const pageDesc = computed(() => userStore.isAdmin ? '这是馆藏的实时状态与今日待办。' : '这是您的借阅与阅读概况。')
const trendTitle = computed(() => userStore.isAdmin ? '近六月借阅流转' : '近六月我的借阅')
const distTitle = computed(() => userStore.isAdmin ? '在办事务状态' : '我的阅读分类')

const formatNumber = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : (v ?? '—')
}

/* 馆藏总量：总册 / 可借 / 在借 */
const collectionStats = computed(() => {
  const c = data.value.collection || {}
  const copies = Number(c.copies) || 0
  const available = Number(c.availableCopies) || 0
  return {
    titles: Number(c.titles) || 0,
    copies,
    available,
    onLoan: Math.max(0, copies - available),
  }
})
const availabilityRate = computed(() => {
  const { copies, available } = collectionStats.value
  return copies > 0 ? Math.round((available / copies) * 100) : 0
})

/* 待办三件套 */
const todoItems = computed(() => [
  {
    key: 'pickup',
    label: '待取书预约',
    value: ops.value.pendingPickup ?? 0,
    unit: '单',
    hint: '读者已申请，等待到馆取书',
    action: '办理',
    color: 'var(--color-primary)',
  },
  {
    key: 'return',
    label: '归还待确认',
    value: ops.value.pendingReturnConfirm ?? 0,
    unit: '单',
    hint: '读者已还书，等待柜台确认',
    action: '办理',
    color: 'var(--color-warning)',
  },
  {
    key: 'overdue',
    label: '逾期未还',
    value: ops.value.overdue ?? 0,
    unit: '本',
    hint: '超期图书，需要催还处理',
    action: '催还',
    color: 'var(--color-danger)',
  },
])

/* 六个月的借出 / 归还合计 */
const trendTotals = computed(() => {
  const sum = (arr) => (arr || []).reduce((s, n) => s + Number(n || 0), 0)
  return {
    borrow: sum(data.value.borrowTrend?.borrow),
    return: sum(data.value.borrowTrend?.return),
  }
})

/* 环图数据：管理员=在办事务，读者=阅读分类 */
const donutData = computed(() => {
  const isAdmin = userStore.isAdmin
  const dist = isAdmin ? (ops.value.activeStatusDist || []) : (data.value.categoryDist || [])
  return dist.map((d, i) => ({
    name: d.name,
    value: Number(d.value) || 0,
    color: isAdmin ? (STATUS_COLORS[d.name] || '#9c958d') : CATE_COLORS[i % CATE_COLORS.length],
  }))
})
const donutTotal = computed(() => donutData.value.reduce((s, d) => s + d.value, 0))
const donutUnit = computed(() => userStore.isAdmin ? '单' : '次')
const donutCaption = computed(() => userStore.isAdmin ? '在办事务' : '借阅分类')

const tooltipBase = computed(() => ({
  backgroundColor: palette.value.elevated,
  borderColor: palette.value.border,
  textStyle: { color: palette.value.text, fontSize: 12 },
  extraCssText: 'box-shadow: 0 6px 20px rgba(44,40,37,0.10); border-radius: 4px;',
}))

const borrowTrendOption = computed(() => {
  const p = palette.value
  return {
    tooltip: {
      trigger: 'axis',
      ...tooltipBase.value,
      axisPointer: { type: 'line', lineStyle: { color: p.border, type: 'dashed' } },
    },
    grid: { left: 8, right: 16, top: 18, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.value.borrowTrend.months || [],
      axisLine: { lineStyle: { color: p.border } },
      axisTick: { show: false },
      axisLabel: { color: p.tertiary, fontSize: 11, margin: 12 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: p.divider, type: 'dashed' } },
      axisLabel: { color: p.tertiary, fontSize: 11 },
    },
    series: [
      {
        name: '借出',
        type: 'line',
        data: data.value.borrowTrend.borrow || [],
        smooth: 0.4,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: false,
        lineStyle: { color: p.primary, width: 2 },
        itemStyle: { color: p.primary, borderColor: p.elevated, borderWidth: 1.5 },
        emphasis: { scale: 1.6 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(58, 90, 107, 0.16)' },
              { offset: 1, color: 'rgba(58, 90, 107, 0)' },
            ],
          },
        },
      },
      {
        name: '归还',
        type: 'line',
        data: data.value.borrowTrend.return || [],
        smooth: 0.4,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: false,
        lineStyle: { color: p.secondary, width: 2, type: 'dashed' },
        itemStyle: { color: p.secondary, borderColor: p.elevated, borderWidth: 1.5 },
        emphasis: { scale: 1.6 },
      },
    ],
  }
})

const sidePieOption = computed(() => {
  const p = palette.value
  return {
    tooltip: {
      trigger: 'item',
      ...tooltipBase.value,
      formatter: `{b}<br/><b>{c}</b> ${donutUnit.value}（{d}%）`,
    },
    series: [
      {
        type: 'pie',
        radius: ['64%', '84%'],
        center: ['50%', '50%'],
        startAngle: 108,
        avoidLabelOverlap: true,
        itemStyle: {
          borderColor: p.elevated,
          borderWidth: 3,
          borderRadius: 2,
        },
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scaleSize: 5 },
        data: donutData.value.map((d) => ({
          name: d.name,
          value: d.value,
          itemStyle: { color: d.color },
        })),
      },
    ],
  }
})

/* 馆藏分类：可借 + 在借 堆叠横条 */
const collectionBarOption = computed(() => {
  const p = palette.value
  const dist = [...(data.value.categoryBookDist || [])].sort((a, b) => a.copies - b.copies)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(148, 138, 126, 0.10)' } },
      ...tooltipBase.value,
      formatter: (params) => {
        const item = dist.find((d) => d.name === params[0].name)
        if (!item) return ''
        const onLoan = Math.max(0, item.copies - item.availableCopies)
        return `${item.name}<br/>总册 <b>${item.copies}</b> · 可借 <b>${item.availableCopies}</b> · 在借 <b>${onLoan}</b><br/>共 ${item.titles} 种`
      },
    },
    grid: { left: 4, right: 54, top: 6, bottom: 4, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: p.divider, type: 'dashed' } },
      axisLabel: { color: p.tertiary, fontSize: 11 },
    },
    yAxis: {
      type: 'category',
      data: dist.map((c) => c.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: p.textSecondary, fontSize: 12, margin: 12 },
    },
    series: [
      {
        name: '可借',
        type: 'bar',
        stack: 'copies',
        data: dist.map((c) => c.availableCopies),
        barWidth: 14,
        itemStyle: { color: p.primary, borderRadius: [3, 0, 0, 3] },
        emphasis: { focus: 'series' },
      },
      {
        name: '在借',
        type: 'bar',
        stack: 'copies',
        data: dist.map((c) => Math.max(0, c.copies - c.availableCopies)),
        barWidth: 14,
        itemStyle: { color: p.warning, borderRadius: [0, 3, 3, 0] },
        emphasis: { focus: 'series' },
        label: {
          show: true,
          position: 'right',
          color: p.tertiary,
          fontSize: 11,
          formatter: (params) => `${dist[params.dataIndex]?.copies ?? 0}`,
        },
      },
    ],
  }
})

const hotMaxCount = computed(() => {
  const list = data.value.hotBooks || []
  return list.length ? Math.max(...list.map((b) => Number(b.count) || 0)) : 1
})
const hotBarWidth = (count) => Math.max(6, Math.round((Number(count) / hotMaxCount.value) * 100))

const noticeDate = (t) => (t ? String(t).substring(5, 10).replace('-', '.') : '')

const loadData = async () => {
  const [stats, noticeData] = await Promise.all([
    dashboardApi.stats(userStore.userType),
    announcementApi.list(),
  ])
  data.value = stats
  notices.value = (noticeData.list || []).slice(0, 8)
}

onMounted(loadData)
</script>

<style scoped>
/* —— 刊头 —— */
.masthead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.masthead-copy {
  min-width: 0;
}

.masthead-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
  font-family: var(--font-mono);
  color: var(--color-text-tertiary);
}

.meta-date {
  font-size: var(--fs-body);
  letter-spacing: 0.04em;
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}

.meta-week {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
}

/* —— 分节 —— */
.section {
  margin-bottom: var(--space-7);
}

.reveal {
  animation: fade-in-up 0.5s var(--ease-out) both;
}

.section:nth-of-type(2) { animation-delay: 0.05s; }
.section:nth-of-type(3) { animation-delay: 0.1s; }
.section:nth-of-type(4) { animation-delay: 0.15s; }
.section:nth-of-type(5) { animation-delay: 0.2s; }

.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.section-index {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.section-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
}

.section-rule {
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

.section-note {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
}

/* —— 概览 —— */
.overview-grid {
  display: grid;
  grid-template-columns: minmax(260px, 4fr) 7fr;
  gap: var(--space-4);
  align-items: stretch;
}

.hero-card {
  display: flex;
  padding: 0;
  overflow: hidden;
  min-height: 220px;
}

.hero-rail {
  width: 46px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-subtle);
  border-right: 1px solid var(--color-border);
}

.rail-text {
  writing-mode: vertical-rl;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.26em;
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.hero-body {
  flex: 1;
  min-width: 0;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.hero-label {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: var(--space-3) 0 var(--space-2);
}

.hero-value .num {
  font-family: var(--font-serif);
  font-size: clamp(2.6rem, 3.6vw, 3.5rem);
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.035em;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.hero-value .unit {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

.hero-sub {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.hero-meter {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px dashed var(--color-border);
}

.meter-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

.meter-rate {
  font-family: var(--font-mono);
  color: var(--color-primary);
}

.meter-track {
  height: 3px;
  border-radius: 2px;
  background: var(--color-bg-muted);
  overflow: hidden;
}

.meter-fill {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-secondary));
  transition: width 0.9s var(--ease-out);
}

.meter-foot {
  margin-top: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 账簿式指标 */
.index-grid {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.index-item {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto auto;
  align-content: center;
  column-gap: var(--space-4);
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--color-divider);
  transition: background var(--duration-fast) var(--ease-soft);
}

.index-item:last-child {
  border-bottom: none;
}

.index-item:hover {
  background: var(--color-bg-subtle);
}

.index-label {
  grid-column: 1;
  grid-row: 1;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  letter-spacing: 0.02em;
}

.index-value {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  text-align: right;
  font-family: var(--font-serif);
  font-size: 1.45rem;
  font-weight: 600;
  line-height: 1.1;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.index-item.danger .index-value {
  color: var(--color-danger);
}

.index-unit {
  font-family: var(--font-sans);
  font-style: normal;
  font-size: var(--fs-caption);
  font-weight: 400;
  color: var(--color-text-tertiary);
  margin-left: 3px;
}

.index-trend {
  grid-column: 1;
  grid-row: 2;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* —— 待办台账 —— */
.todo-band {
  display: grid;
  grid-template-columns: repeat(3, 1fr) 1.1fr;
  padding: 0;
  overflow: hidden;
}

.todo-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--space-5);
  border-right: 1px solid var(--color-divider);
  text-decoration: none;
  color: inherit;
  transition: background var(--duration) var(--ease-soft);
}

.todo-cell:hover {
  background: var(--color-bg-subtle);
}

.todo-cell.empty {
  opacity: 0.55;
}

.todo-cell.empty:hover {
  opacity: 0.8;
}

.todo-label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.todo-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.todo-num {
  font-family: var(--font-serif);
  font-size: 2.3rem;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.todo-num i {
  font-family: var(--font-sans);
  font-style: normal;
  font-size: var(--fs-caption);
  font-weight: 400;
  color: var(--color-text-tertiary);
  margin-left: 4px;
}

.todo-hint {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  line-height: var(--lh-snug);
}

.todo-go {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-top: auto;
  padding-top: var(--space-3);
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  transition: gap var(--duration) var(--ease-soft), color var(--duration) var(--ease-soft);
}

.todo-cell:hover .todo-go {
  gap: 7px;
  color: var(--color-primary);
}

.todo-metrics {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  background: var(--color-bg-subtle);
}

.metric {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}

.metric-num {
  font-family: var(--font-serif);
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
}

.metric-label {
  font-size: var(--fs-caption);
  line-height: 1.4;
  color: var(--color-text-tertiary);
}

/* —— 图表面板 —— */
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.9fr) minmax(280px, 1fr);
  gap: var(--space-4);
}

.panel {
  display: flex;
  flex-direction: column;
  padding: var(--space-5);
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.panel-eyebrow {
  display: block;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.panel-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  margin-top: 2px;
}

.panel-figures {
  display: flex;
  gap: var(--space-4);
  flex-shrink: 0;
}

.figure {
  display: flex;
  align-items: baseline;
  gap: 5px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.figure b {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.dot.primary { background: var(--color-primary); }
.dot.secondary { background: var(--color-secondary); }
.dot.gold { background: var(--color-warning); }

.chart-canvas {
  width: 100%;
  height: 268px;
}

.collection-canvas {
  height: 232px;
}

/* 环比图与自绘图例 */
.donut-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--space-5);
  min-height: 220px;
}

.donut-chart {
  position: relative;
  width: 168px;
  height: 168px;
  flex-shrink: 0;
}

.donut-canvas {
  width: 100%;
  height: 100%;
}

.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.donut-total {
  font-family: var(--font-serif);
  font-size: 1.9rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.donut-caption {
  margin-top: 5px;
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.donut-legend {
  list-style: none;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.donut-legend li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-caption);
}

.legend-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.legend-name {
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-value {
  font-family: var(--font-serif);
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.legend-value i {
  font-family: var(--font-sans);
  font-style: normal;
  font-size: 10px;
  color: var(--color-text-tertiary);
  margin-left: 2px;
}

/* 馆藏结构与图例 */
.coll-figures {
  display: flex;
  gap: var(--space-5);
  flex-shrink: 0;
}

.coll-figure {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.coll-num {
  font-family: var(--font-serif);
  font-size: 1.3rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.coll-num.primary { color: var(--color-primary); }
.coll-num.warning { color: var(--color-warning); }

.coll-label {
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.collection-legend {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin: calc(-1 * var(--space-2)) 0 var(--space-3);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.legend-item.hint {
  margin-left: auto;
  color: var(--color-text-tertiary);
  opacity: 0.8;
}

/* —— 底部两栏 —— */
.bottom-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(280px, 1fr);
  gap: var(--space-4);
}

.more-link {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  flex-shrink: 0;
  transition: color var(--duration-fast) var(--ease-soft);
}

.more-link:hover {
  color: var(--color-primary);
}

.hot-list,
.notice-list {
  list-style: none;
  max-height: 424px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border-strong) transparent;
  margin-right: calc(-1 * var(--space-2));
  padding-right: var(--space-2);
}

.hot-list::-webkit-scrollbar,
.notice-list::-webkit-scrollbar {
  width: 5px;
}

.hot-list::-webkit-scrollbar-thumb,
.notice-list::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: var(--color-border-strong);
}

.hot-list::-webkit-scrollbar-track,
.notice-list::-webkit-scrollbar-track {
  background: transparent;
}

.hot-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-divider);
}

.hot-item:last-child {
  border-bottom: none;
}

.rank-no {
  width: 24px;
  flex-shrink: 0;
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.rank-no.top {
  color: var(--color-primary);
  font-weight: 600;
}

.hot-cover {
  width: 34px;
  height: 46px;
  flex-shrink: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
}

.hot-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-fallback {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  color: var(--color-text-tertiary);
}

.hot-main {
  flex: 1;
  min-width: 0;
}

.hot-title {
  font-size: var(--fs-body);
  color: var(--color-text);
  margin-bottom: 2px;
}

.hot-sub {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: 7px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.hot-sep {
  opacity: 0.6;
}

.hot-bar {
  height: 3px;
  border-radius: 2px;
  background: var(--color-bg-subtle);
  overflow: hidden;
}

.hot-bar-fill {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--color-primary), rgba(58, 90, 107, 0.35));
  transition: width 0.7s var(--ease-out);
}

.hot-count {
  display: flex;
  align-items: baseline;
  gap: 2px;
  flex-shrink: 0;
}

.count-num {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
}

.count-unit {
  font-size: 10px;
  color: var(--color-text-tertiary);
}

.notice-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: start;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-divider);
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-date {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  padding-top: 2px;
  font-variant-numeric: tabular-nums;
}

.notice-body {
  min-width: 0;
}

.notice-title {
  font-size: var(--fs-body);
  line-height: var(--lh-snug);
  color: var(--color-text);
}

.pin-dot {
  display: block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-danger);
  margin-top: 7px;
  box-shadow: 0 0 0 3px rgba(168, 90, 74, 0.14);
}

.list-empty {
  padding: var(--space-6) 0;
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* —— 响应式 —— */
@media (max-width: 1200px) {
  .overview-grid {
    grid-template-columns: minmax(220px, 4fr) 7fr;
  }

  .todo-band {
    grid-template-columns: repeat(3, 1fr);
  }

  .todo-metrics {
    grid-column: 1 / -1;
    flex-direction: row;
    justify-content: space-around;
    border-top: 1px solid var(--color-divider);
  }

  .todo-cell:nth-of-type(3) {
    border-right: none;
  }
}

@media (max-width: 1024px) {
  .overview-grid,
  .chart-grid,
  .bottom-grid {
    grid-template-columns: 1fr;
  }

  .hero-card {
    min-height: 0;
  }

  .donut-wrap {
    justify-content: center;
  }
}

@media (max-width: 768px) {
  .masthead {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
  }

  .masthead-meta {
    flex-direction: row;
    align-items: baseline;
    gap: var(--space-3);
  }

  .section {
    margin-bottom: var(--space-6);
  }

  .section-note {
    display: none;
  }

  .todo-band {
    grid-template-columns: 1fr;
  }

  .todo-cell {
    border-right: none;
    border-bottom: 1px solid var(--color-divider);
  }

  .todo-cell:nth-of-type(3) {
    border-bottom: 1px solid var(--color-divider);
  }

  .chart-canvas {
    height: 230px;
  }

  .collection-canvas {
    height: 210px;
  }

  .panel-figures {
    gap: var(--space-3);
  }

  .coll-figures {
    gap: var(--space-4);
  }

  .collection-legend {
    flex-wrap: wrap;
    row-gap: var(--space-2);
  }

  .legend-item.hint {
    margin-left: 0;
  }
}

@media (max-width: 480px) {
  .hero-body {
    padding: var(--space-4);
  }

  .index-item {
    padding: var(--space-3) var(--space-4);
  }

  .panel {
    padding: var(--space-4);
  }

  .donut-wrap {
    flex-direction: column;
    gap: var(--space-4);
  }

  .donut-legend {
    width: 100%;
  }

  .chart-canvas {
    height: 200px;
  }
}
</style>
