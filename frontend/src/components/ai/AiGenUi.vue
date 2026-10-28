<!--
  M5 · 生成式 UI 渲染器
  把 AI 工具返回的 JSON 快照渲染成可读组件（图书卡、排行榜、额度条、表格、图表…），
  而非让用户去读原始 JSON。

  设计要点：
  · 按快照里的 type 字段分发；无 type 或未映射的类型回落到默认 JSON 视图（向后兼容）
  · 组件按需引入，echarts 仅在需要图表时异步加载，不进主包
  · 视觉沿用项目设计语言（暖纸调 / 靛蓝主色 / 克制圆角）
-->
<template>
  <div class="gen-ui">
    <!-- 图书卡片网格 -->
    <div v-if="type === 'book_grid'" class="gi-book-grid">
      <div v-for="(b, i) in items" :key="i" class="gi-book" :title="b.publisher ? `出版社：${b.publisher}` : ''">
        <div class="gi-book-cover">
          <img v-if="b.coverUrl" :src="b.coverUrl" :alt="b.title" loading="lazy" />
          <div v-else class="gi-book-cover-fallback">
            <el-icon><Collection /></el-icon>
          </div>
        </div>
        <div class="gi-book-info">
          <div class="gi-book-title" :title="b.title">{{ b.title }}</div>
          <div class="gi-book-author">{{ b.author || '佚名' }}</div>
          <div class="gi-book-meta">
            <span v-if="b.availableCount !== undefined && b.availableCount !== null"
                  class="gi-stock" :class="{ empty: b.availableCount <= 0 }">
              {{ b.availableCount > 0 ? `可借 ${b.availableCount}/${b.totalCount ?? '?'}` : '暂无可借' }}
            </span>
            <span v-if="b.category" class="gi-cat">{{ b.category }}</span>
          </div>
          <div v-if="b.location || b.publisher" class="gi-book-extra">
            <span v-if="b.location" class="gi-book-loc" :title="'馆藏位置：' + b.location">
              <el-icon><Location /></el-icon>{{ b.location }}
            </span>
            <span v-if="b.publisher" class="gi-book-pub">{{ b.publisher }}</span>
          </div>
        </div>
      </div>
      <div v-if="!items.length" class="gi-empty">未查到相关图书</div>
    </div>

    <!-- 排行榜 -->
    <div v-else-if="type === 'rank_list'" class="gi-rank">
      <div v-for="(b, i) in items" :key="i" class="gi-rank-row">
        <span class="gi-rank-no" :class="'no' + (i + 1)">{{ i + 1 }}</span>
        <div class="gi-rank-main">
          <div class="gi-rank-title">{{ b.title }}</div>
          <div class="gi-rank-sub">{{ b.author || '佚名' }}</div>
        </div>
        <span v-if="b.borrowCount !== undefined" class="gi-rank-count">{{ b.borrowCount }} 次</span>
      </div>
      <div v-if="!items.length" class="gi-empty">暂无排行数据</div>
    </div>

    <!-- 借阅额度进度条 -->
    <div v-else-if="type === 'quota_bar'" class="gi-quota">
      <div class="gi-quota-nums">
        <span class="gi-quota-label">已用</span>
        <span class="gi-quota-used">{{ used }}</span>
        <span class="gi-quota-sep">/</span>
        <span class="gi-quota-max">{{ max }}</span>
        <span class="gi-quota-label">本</span>
      </div>
      <div class="gi-quota-track">
        <div class="gi-quota-fill" :class="{ warn: remaining <= 0 }" :style="{ width: percent + '%' }"></div>
      </div>
      <div class="gi-quota-foot">
        <span>剩余 <b>{{ remaining }}</b> 本</span>
        <span v-if="overdueCount > 0" class="gi-quota-overdue">
          逾期 {{ overdueCount }} 本<template v-if="pendingReturnOverdue > 0">（含归还待确认 {{ pendingReturnOverdue }} 本）</template>
        </span>
        <span v-else class="gi-quota-ok">状态正常</span>
      </div>
    </div>

    <!-- 分类网格 -->
    <div v-else-if="type === 'category_grid'" class="gi-cat-grid">
      <div v-for="(c, i) in items" :key="i" class="gi-cat-item">
        <span class="gi-cat-name">{{ c.categoryName || c.name }}</span>
        <span v-if="c.bookCount !== undefined" class="gi-cat-count">{{ c.bookCount }} 种</span>
      </div>
      <div v-if="!items.length" class="gi-empty">暂无分类数据</div>
    </div>

    <!-- 公告列表 -->
    <div v-else-if="type === 'announce_list'" class="gi-announce">
      <div v-for="(a, i) in items" :key="i" class="gi-announce-item">
        <div class="gi-announce-title">{{ a.title }}</div>
        <div class="gi-announce-time">{{ (a.createTime || a.publishTime || '').slice(0, 10) }}</div>
      </div>
      <div v-if="!items.length" class="gi-empty">暂无公告</div>
    </div>

    <!-- 借阅记录表：专属渲染（状态徽章 / 读者列 / 逾期与待取书时间分色） -->
    <div v-else-if="type === 'borrow_table'" class="gi-table-wrap">
      <table class="gi-table">
        <thead>
          <tr>
            <th>借阅码</th>
            <th v-if="hasUserName">读者</th>
            <th>书名</th>
            <th>状态</th>
            <th>{{ hasPickupDeadline ? '取书截止' : '应还时间' }}</th>
            <th v-if="hasRenewInfo">续借</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="ri">
            <td class="mono">{{ row.borrowNo ?? '—' }}</td>
            <td v-if="hasUserName">{{ row.userName ?? '—' }}</td>
            <td class="gi-bt-title" :title="row.bookTitle">{{ row.bookTitle ?? '—' }}</td>
            <td>
              <span class="gi-status" :class="statusClass(row.status, row.dueTime)">
                {{ row.status ?? '—' }}
              </span>
            </td>
            <td :class="dueCellClass(row)">
              {{ (hasPickupDeadline ? row.pickupDeadline : row.dueTime) ? fmtDate(hasPickupDeadline ? row.pickupDeadline : row.dueTime) : '—' }}
            </td>
            <td v-if="hasRenewInfo" class="mono">
              {{ row.renewCount != null ? `${row.renewCount}/${row.renewMaxCount ?? 2}` : '—' }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!rows.length" class="gi-empty">暂无借阅记录</div>
    </div>

    <!-- 通用表格（罚款 / 读者） -->
    <div v-else-if="type === 'fine_table' || type === 'user_table'" class="gi-table-wrap">
      <table class="gi-table">
        <thead>
          <tr>
            <th v-for="(h, ci) in headers" :key="ci">{{ h.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="ri">
            <td v-for="(h, ci) in headers" :key="ci">{{ row[h.key] ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!rows.length" class="gi-empty">暂无记录</div>
    </div>

    <!-- 写操作结果：柜台办理 / 催还等逐项执行结果 -->
    <div v-else-if="type === 'operation_result'" class="gi-op-result">
      <div v-for="(r, ri) in items" :key="ri" class="gi-op-item" :class="{ fail: r.success === false }">
        <span class="gi-op-dot" :class="{ fail: r.success === false }"></span>
        <span class="gi-op-title">{{ r.borrowNo || r.title || r.tool }}</span>
        <span class="gi-op-msg">{{ r.message || (r.success === false ? '失败' : '成功') }}</span>
      </div>
      <div v-if="!items.length" class="gi-empty">暂无操作结果</div>
    </div>

    <!-- 统计看板（ECharts 图表） -->
    <div v-else-if="type === 'stats_dashboard'" class="gi-stats">
      <div class="gi-stats-kpis">
        <div v-for="(k, ki) in kpis" :key="ki" class="gi-kpi">
          <div class="gi-kpi-value">{{ k.value }}</div>
          <div class="gi-kpi-label">{{ k.label }}</div>
        </div>
      </div>
      <div v-if="chartOption" class="gi-chart" ref="chartRef"></div>
      <div v-else-if="!kpis.length" class="gi-empty">暂无统计数据</div>
    </div>

    <!-- 兜底：JSON 展开视图（与原有行为一致） -->
    <div v-else class="gi-fallback">
      <pre>{{ pretty }}</pre>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { Collection, Location } from '@element-plus/icons-vue'

const props = defineProps({
  // 快照对象（后端 tool 事件 data 解析后的结果）
  snapshot: { type: Object, default: null },
})

/** 快照类型（后端在 data 里声明 type） */
const type = computed(() => props.snapshot?.type || '')
/** 条目数组 */
const items = computed(() => (Array.isArray(props.snapshot?.items) ? props.snapshot.items : []))

/** 额度相关数值 */
const max = computed(() => props.snapshot?.maxBooks ?? 0)
const used = computed(() => props.snapshot?.used ?? 0)
const remaining = computed(() => props.snapshot?.remaining ?? 0)
const overdueCount = computed(() => props.snapshot?.overdueCount ?? 0)
const pendingReturnOverdue = computed(() => props.snapshot?.pendingReturnOverdue ?? 0)
const percent = computed(() => {
  const m = Number(max.value) || 0
  if (m <= 0) return 0
  return Math.min(100, Math.round(((Number(used.value) || 0) / m) * 100))
})

/** 表格列定义：按类型给出表头与取值键 */
const TABLE_HEADERS = {
  borrow_table: [
    { key: 'borrowNo', label: '借阅码' },
    { key: 'bookTitle', label: '书名' },
    { key: 'status', label: '状态' },
    { key: 'dueTime', label: '应还时间' },
  ],
  fine_table: [
    { key: 'amount', label: '金额' },
    { key: 'overdueDays', label: '逾期天数' },
    { key: 'status', label: '状态' },
  ],
  user_table: [
    { key: 'realName', label: '姓名' },
    { key: 'userType', label: '身份' },
    { key: 'studentNo', label: '学号/工号' },
    { key: 'department', label: '院系' },
  ],
}
const headers = computed(() => TABLE_HEADERS[type.value] || [])

/** 表格行数据（borrow_table / fine_table / user_table 共用 items 数组） */
const rows = computed(() => (Array.isArray(props.snapshot?.items) ? props.snapshot.items : []))

/* —— 借阅表专属逻辑 —— */
// 快照条目是否含读者列（adminQueryBorrows 有 userName，queryMyBorrows 没有）
const hasUserName = computed(() => rows.value.some((r) => r.userName))
// 待取书记录带 pickupDeadline（取书截止）；已有应还时间的按应还时间列展示
const hasPickupDeadline = computed(() =>
  rows.value.some((r) => r.pickupDeadline) && !rows.value.some((r) => r.dueTime && r.status === '借阅中'))
const hasRenewInfo = computed(() => rows.value.some((r) => r.renewCount != null))

/** 状态 → 徽章样式类（逾期红 / 待取书蓝 / 借阅中绿 / 已归还灰 / 取消浅灰） */
const statusClass = (status, dueTime) => {
  const s = String(status || '')
  if (s.includes('逾期')) return 'st-overdue'
  if (s.includes('待取书')) return 'st-pickup'
  if (s.includes('借阅中')) {
    // 借阅中但应还时间已过：按逾期提示
    if (dueTime && new Date(dueTime) < new Date()) return 'st-overdue'
    return 'st-active'
  }
  if (s.includes('待确认')) return 'st-pending'
  if (s.includes('已归还')) return 'st-done'
  return 'st-default'
}

/** 时间单元格分色：逾期红 / 临期（3 天内）琥珀 */
const dueCellClass = (row) => {
  const t = hasPickupDeadline.value ? row.pickupDeadline : row.dueTime
  if (!t) return ''
  const diff = new Date(t) - new Date()
  if (diff < 0 && String(row.status || '').includes('借阅中')) return 'due-overdue'
  if (diff > 0 && diff < 3 * 86400_000 && String(row.status || '').includes('借阅中')) return 'due-soon'
  return ''
}

const fmtDate = (t) => {
  const d = new Date(t)
  if (isNaN(d)) return t
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 统计看板的 KPI 列表 */
const kpis = computed(() => {
  const s = props.snapshot || {}
  const list = []
  const push = (label, value) => {
    if (value !== undefined && value !== null && value !== '') list.push({ label, value })
  }
  // 指标卡片：后端已把统计卡整理成 { label, value, unit, note } 字典，优先使用
  const metrics = s.metrics || {}
  const metricEntries = Object.entries(metrics).filter(([, v]) => v && typeof v === 'object')
  metricEntries.forEach(([k, v]) => {
    push(v.label || k, v.unit ? `${v.value} ${v.unit}` : v.value)
  })
  if (!metricEntries.length) {
    // 无 metrics 时退回馆藏与运营字段
    const collection = s.collection || {}
    push('馆藏种数', collection.titles)
    push('总册数', collection.copies)
    push('当前可借', collection.availableCopies)
    const ops = s.operations || {}
    push('待取书', ops.pendingPickup)
    push('逾期未还', ops.overdue)
    push('今日借出', ops.todayBorrow)
    push('馆外流通', ops.outsideCount)
    push('未缴罚款', ops.unpaidFineAmount != null ? `${ops.unpaidFineAmount} 元` : null)
  }
  // 扁平字段兜底
  push('读者总数', s.totalUsers ?? s.userCount)
  push('借阅中', s.activeBorrows ?? s.borrowing)
  return list
})

/** 图表配置：由快照里的分类分布/趋势数据生成 */
const chartOption = computed(() => {
  const s = props.snapshot || {}
  // 分类藏书分布：categoryBookDist = [{ name, titles, copies, availableCopies }]
  const dist = s.categoryBookDist || s.categoryDist || s.distribution
  if (Array.isArray(dist) && dist.length) {
    return {
      kind: 'bar',
      title: '分类藏书分布（种）',
      data: dist.map((d) => ({
        name: d.name || d.categoryName,
        value: d.titles ?? d.count ?? d.value ?? d.copies ?? 0,
      })),
    }
  }
  // 借阅趋势：borrowTrend = [{ name/month, borrow/return/count }]
  const trend = s.borrowTrend || s.trend || s.monthlyTrend
  if (Array.isArray(trend) && trend.length) {
    return {
      kind: 'line',
      title: '借阅趋势',
      data: trend.map((d) => ({
        name: d.name || d.month,
        value: d.borrow ?? d.count ?? d.value ?? 0,
      })),
    }
  }
  return null
})

/** 兜底 JSON 展示 */
const pretty = computed(() => {
  try {
    return JSON.stringify(props.snapshot, null, 2)
  } catch {
    return String(props.snapshot)
  }
})

/* —— ECharts 图表：按需异步加载，不进主包 —— */
const chartRef = ref(null)
let chart = null
let echartsMod = null

const renderChart = async () => {
  const opt = chartOption.value
  if (!opt || !chartRef.value) return
  try {
    if (!echartsMod) {
      echartsMod = await import('echarts/core')
      const { BarChart, LineChart } = await import('echarts/charts')
      const { GridComponent, TooltipComponent, LegendComponent, TitleComponent } = await import('echarts/components')
      const { CanvasRenderer } = await import('echarts/renderers')
      echartsMod.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, CanvasRenderer])
    }
    const echarts = echartsMod.default || echartsMod
    if (!chart) chart = echarts.init(chartRef.value)
    const isLine = opt.kind === 'line'
    chart.setOption({
      title: {
        text: opt.title,
        left: 0,
        top: 0,
        textStyle: { fontSize: 11, fontWeight: 500, color: 'rgba(140,130,120,0.95)' },
      },
      grid: { left: 8, right: 12, top: 34, bottom: 4, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: opt.data.map((d) => d.name),
        axisLine: { lineStyle: { color: 'rgba(140,130,120,0.35)' } },
        axisLabel: { color: 'rgba(140,130,120,0.9)', fontSize: 11, interval: 0, rotate: opt.data.length > 5 ? 24 : 0 },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: 'rgba(140,130,120,0.14)' } },
        axisLabel: { color: 'rgba(140,130,120,0.9)', fontSize: 11 },
      },
      series: [
        {
          type: isLine ? 'line' : 'bar',
          data: opt.data.map((d) => d.value),
          smooth: isLine,
          barMaxWidth: 26,
          itemStyle: { color: '#3a5a6b', borderRadius: [3, 3, 0, 0] },
          lineStyle: { color: '#3a5a6b', width: 2 },
          areaStyle: isLine ? { color: 'rgba(58,90,107,0.10)' } : undefined,
        },
      ],
    })
  } catch (e) {
    // 图表加载失败不影响其余内容
    console.warn('[生成式UI] 图表渲染失败', e)
  }
}

watch(
  () => [type.value, chartOption.value],
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true }
)

onBeforeUnmount(() => {
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.gen-ui {
  margin-top: 6px;
  font-size: 12px;
}

/* —— 图书卡网格 —— */
.gi-book-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
}
.gi-book {
  display: flex;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--color-border, #e8e3dc);
  border-radius: var(--radius, 4px);
  background: var(--color-bg-elevated, #fff);
  transition: border-color 150ms cubic-bezier(0.25, 0.1, 0.25, 1);
}
.gi-book:hover { border-color: var(--color-primary, #3a5a6b); }
.gi-book-cover {
  flex-shrink: 0;
  width: 34px;
  height: 46px;
  border-radius: 3px;
  overflow: hidden;
  background: var(--color-bg-muted, #ece6dd);
  display: flex;
  align-items: center;
  justify-content: center;
}
.gi-book-cover img { width: 100%; height: 100%; object-fit: cover; }
.gi-book-cover-fallback { color: var(--color-text-tertiary, #9c958d); font-size: 16px; }
.gi-book-info { min-width: 0; flex: 1; }
.gi-book-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text, #2c2825);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gi-book-author {
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gi-book-meta { margin-top: 4px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.gi-stock {
  font-size: 11px;
  color: var(--color-success, #5d8a6b);
  background: rgba(93, 138, 107, 0.12);
  padding: 1px 6px;
  border-radius: 999px;
}
.gi-stock.empty { color: var(--color-danger, #a85a4a); background: rgba(168, 90, 74, 0.12); }
.gi-cat {
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
  border: 1px solid var(--color-border, #e8e3dc);
  padding: 0 6px;
  border-radius: 999px;
}

/* —— 排行榜 —— */
.gi-rank { display: flex; flex-direction: column; gap: 4px; }
.gi-rank-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: var(--radius, 4px);
  background: var(--color-bg-subtle, #f3efe9);
}
.gi-rank-no {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-family: var(--font-mono, monospace);
  background: var(--color-bg-muted, #ece6dd);
  color: var(--color-text-tertiary, #9c958d);
}
.gi-rank-no.no1 { background: var(--color-primary, #3a5a6b); color: #fff; }
.gi-rank-no.no2 { background: var(--color-secondary, #7a9270); color: #fff; }
.gi-rank-no.no3 { background: var(--color-warning, #c4904a); color: #fff; }
.gi-rank-main { flex: 1; min-width: 0; }
.gi-rank-title {
  font-size: 12px;
  color: var(--color-text, #2c2825);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gi-rank-sub { font-size: 11px; color: var(--color-text-tertiary, #9c958d); }
.gi-rank-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--color-primary, #3a5a6b);
  font-family: var(--font-mono, monospace);
}

/* —— 额度条 —— */
.gi-quota {
  padding: 10px 12px;
  border: 1px solid var(--color-border, #e8e3dc);
  border-radius: var(--radius, 4px);
  background: var(--color-bg-elevated, #fff);
}
.gi-quota-nums { display: flex; align-items: baseline; gap: 4px; }
.gi-quota-used { font-size: 18px; font-weight: 600; color: var(--color-primary, #3a5a6b); font-family: var(--font-mono, monospace); }
.gi-quota-sep { color: var(--color-text-tertiary, #9c958d); }
.gi-quota-max { font-size: 14px; color: var(--color-text-secondary, #6b6560); font-family: var(--font-mono, monospace); }
.gi-quota-label { font-size: 11px; color: var(--color-text-tertiary, #9c958d); margin-left: 4px; }
.gi-quota-track {
  margin-top: 8px;
  height: 5px;
  border-radius: 999px;
  background: var(--color-bg-muted, #ece6dd);
  overflow: hidden;
}
.gi-quota-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--color-primary, #3a5a6b);
  transition: width 450ms cubic-bezier(0.16, 1, 0.3, 1);
}
.gi-quota-fill.warn { background: var(--color-danger, #a85a4a); }
.gi-quota-foot {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
}
.gi-quota-overdue { color: var(--color-danger, #a85a4a); }
.gi-quota-ok { color: var(--color-success, #5d8a6b); }

/* —— 分类网格 —— */
.gi-cat-grid { display: flex; flex-wrap: wrap; gap: 6px; }
.gi-cat-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border, #e8e3dc);
  background: var(--color-bg-elevated, #fff);
}
.gi-cat-name { font-size: 12px; color: var(--color-text, #2c2825); }
.gi-cat-count { font-size: 11px; color: var(--color-text-tertiary, #9c958d); font-family: var(--font-mono, monospace); }

/* —— 公告 —— */
.gi-announce { display: flex; flex-direction: column; gap: 4px; }
.gi-announce-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 8px;
  border-left: 2px solid var(--color-border-strong, #d4cdc2);
  background: var(--color-bg-subtle, #f3efe9);
  border-radius: 0 var(--radius, 4px) var(--radius, 4px) 0;
}
.gi-announce-title {
  font-size: 12px;
  color: var(--color-text, #2c2825);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gi-announce-time { flex-shrink: 0; font-size: 11px; color: var(--color-text-tertiary, #9c958d); font-family: var(--font-mono, monospace); }

/* —— 表格 —— */
.gi-table-wrap { overflow-x: auto; border: 1px solid var(--color-border, #e8e3dc); border-radius: var(--radius, 4px); }
.gi-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.gi-table th {
  text-align: left;
  padding: 6px 8px;
  background: var(--color-bg-subtle, #f3efe9);
  color: var(--color-text-tertiary, #9c958d);
  font-weight: 500;
  white-space: nowrap;
  border-bottom: 1px solid var(--color-border, #e8e3dc);
}
.gi-table td {
  padding: 5px 8px;
  color: var(--color-text-secondary, #6b6560);
  border-bottom: 1px solid var(--color-divider, #ece6dd);
  white-space: nowrap;
}
.gi-table tr:last-child td { border-bottom: none; }
.gi-table td.mono { font-family: var(--font-mono, monospace); font-size: 10.5px; }
.gi-bt-title {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--color-text, #2c2825);
}

/* 借阅表：状态徽章 */
.gi-status {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10.5px;
  line-height: 1.6;
}
.gi-status.st-overdue { color: #a85a4a; background: rgba(168, 90, 74, 0.13); font-weight: 600; }
.gi-status.st-pickup { color: #3a5a6b; background: rgba(58, 90, 107, 0.12); }
.gi-status.st-active { color: #5d8a6b; background: rgba(93, 138, 107, 0.13); }
.gi-status.st-pending { color: #a8843c; background: rgba(196, 144, 74, 0.14); }
.gi-status.st-done { color: var(--color-text-tertiary, #9c958d); background: var(--color-bg-muted, #ece6dd); }
.gi-status.st-default { color: var(--color-text-secondary, #6b6560); background: var(--color-bg-subtle, #f3efe9); }
/* 借阅表：时间分色 */
.gi-table td.due-overdue { color: #a85a4a; font-weight: 600; }
.gi-table td.due-soon { color: #a8843c; }

/* 图书卡扩展行：馆藏位置 / 出版社 */
.gi-book-extra {
  margin-top: 3px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: var(--color-text-tertiary, #9c958d);
  min-width: 0;
}
.gi-book-loc {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gi-book-pub {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 1;
}

/* 写操作结果：逐项列表 */
.gi-op-result { display: flex; flex-direction: column; gap: 3px; }
.gi-op-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 8px;
  border-radius: var(--radius, 4px);
  background: var(--color-bg-subtle, #f3efe9);
  font-size: 11.5px;
}
.gi-op-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-success, #5d8a6b);
  align-self: center;
}
.gi-op-dot.fail { background: var(--color-danger, #a85a4a); }
.gi-op-title {
  font-family: var(--font-mono, monospace);
  color: var(--color-text, #2c2825);
  flex-shrink: 0;
}
.gi-op-item.fail .gi-op-title { color: var(--color-danger, #a85a4a); }
.gi-op-msg {
  color: var(--color-text-secondary, #6b6560);
  text-align: right;
  flex: 1;
  word-break: break-all;
}
.gi-op-item.fail .gi-op-msg { color: var(--color-danger, #a85a4a); }

/* —— 统计看板 —— */
.gi-stats { display: flex; flex-direction: column; gap: 10px; }
.gi-stats-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(84px, 1fr)); gap: 8px; }
.gi-kpi {
  padding: 8px 10px;
  border: 1px solid var(--color-border, #e8e3dc);
  border-radius: var(--radius, 4px);
  background: var(--color-bg-elevated, #fff);
}
.gi-kpi-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-primary, #3a5a6b);
  font-family: var(--font-mono, monospace);
  line-height: 1.3;
}
.gi-kpi-label { font-size: 11px; color: var(--color-text-tertiary, #9c958d); margin-top: 2px; }
.gi-chart { width: 100%; height: 180px; }

/* —— 兜底 & 空态 —— */
.gi-fallback pre {
  margin: 0;
  padding: 8px;
  border-radius: var(--radius, 4px);
  background: var(--color-bg-subtle, #f3efe9);
  font-size: 11px;
  color: var(--color-text-secondary, #6b6560);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
.gi-empty {
  padding: 10px;
  text-align: center;
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
}
</style>
