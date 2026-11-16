<template>
  <div class="page fine-page" ref="pageRef">
    <!-- 页头：左侧标题，右侧待缴笔数 -->
    <header class="page-header fine-head">
      <div class="head-text">
        <span class="eyebrow">罚款 · PENALTY</span>
        <h1>罚款记录</h1>
        <p class="desc">逾期产生的费用，结清后自动归档。</p>
      </div>
      <div class="head-figure" aria-label="待缴笔数">
        <span class="fig-num">{{ unpaidCount }}</span>
        <span class="fig-label">待缴笔数</span>
      </div>
    </header>

    <!-- 结算单：待缴为主视觉，右侧为已缴与费率 -->
    <section class="statement" :class="{ 'is-clear': unpaidCount === 0 }" aria-label="罚款结算概览">
      <div class="stmt-main">
        <span class="stmt-eyebrow">OUTSTANDING · 待缴合计</span>
        <div class="stmt-amount">
          <span class="cur">¥</span>
          <span class="num">{{ unpaidTotal.toFixed(2) }}</span>
        </div>
        <p v-if="unpaidCount" class="stmt-note">
          共 <b>{{ unpaidCount }}</b> 笔未结，请到服务台一并缴清核销。
        </p>
        <p v-else class="stmt-note">账目已结清，无待缴款项。</p>
      </div>

      <div class="stmt-side">
        <div class="stmt-metric">
          <span class="m-label">已缴合计</span>
          <span class="m-value">¥{{ paidTotal.toFixed(2) }}</span>
          <span class="m-sub">{{ paidCount }} 笔已结清</span>
        </div>
        <div class="stmt-metric">
          <span class="m-label">计费费率</span>
          <span class="m-value">{{ fineRateText }}<span class="unit">元 / 天</span></span>
          <span class="m-sub">自应还日起按日累计</span>
        </div>
      </div>
    </section>

    <!-- 计费规则 -->
    <div class="rules-label eyebrow">计费规则 · RULES</div>
    <section class="rules" aria-label="计费规则">
      <div class="rule">
        <span class="rule-no" aria-hidden="true">壹</span>
        <div class="rule-body">
          <h2 class="rule-title">逾期罚款</h2>
          <p class="rule-desc">
            图书逾期归还，自应还日期起按 <b>{{ fineRateText }} 元 / 天</b> 计收滞纳金，归还确认时一并结算。
          </p>
        </div>
      </div>
      <div class="rule">
        <span class="rule-no" aria-hidden="true">贰</span>
        <div class="rule-body">
          <h2 class="rule-title">丢失 / 损坏赔偿</h2>
          <p class="rule-desc">图书丢失或污损，须按图书定价赔偿，成色折算以服务台现场核定为准。</p>
        </div>
      </div>
      <div class="rule">
        <span class="rule-no" aria-hidden="true">叁</span>
        <div class="rule-body">
          <h2 class="rule-title">缴纳方式</h2>
          <p class="rule-desc">罚款请在服务台缴清核销，未缴罚款将记录在您的账户中。</p>
        </div>
      </div>
    </section>

    <!-- 状态分段：点选即筛选 -->
    <nav class="band" aria-label="按缴纳状态筛选">
      <button
        v-for="t in STATUS_TABS"
        :key="t.key"
        type="button"
        class="band-item"
        :class="{ active: statusFilter === t.value }"
        :aria-pressed="statusFilter === t.value"
        @click="statusFilter = t.value"
      >
        <span class="band-label">{{ t.label }}</span>
        <span class="band-en">{{ t.en }}</span>
        <span class="band-count">{{ tabCount(t.value) }}</span>
      </button>
    </nav>

    <!-- 罚款账册 -->
    <section v-if="filteredList.length" class="ledger" aria-label="罚款记录列表">
      <div class="ledger-headrow">
        <span class="col-no">NO.</span>
        <span class="col-book">图书 / 借阅信息</span>
        <span class="col-days">逾期</span>
        <span class="col-amount">金额</span>
        <span class="col-state">状态</span>
        <span class="col-ops">操作</span>
      </div>

      <div class="ledger-body">
        <article
          v-for="(row, i) in pagedList"
          :key="row.id"
          class="entry"
          :data-state="row.status === 0 ? 'unpaid' : 'paid'"
          :style="{ '--i': Math.min(i, 9) }"
        >
          <span class="ordinal">{{ ordinal(i) }}</span>

          <div class="cell-book">
            <h3 class="book-title" :title="row.bookTitle">{{ row.bookTitle }}</h3>
            <div class="book-meta">
              <span v-if="isAdmin" class="meta-user">{{ row.userName }}</span>
              <span class="meta-no">{{ row.borrowNo }}</span>
            </div>
          </div>

          <div class="cell-days">
            <span class="days-num">{{ row.daysOverdue }}</span>
            <span class="days-unit">天</span>
            <span class="days-note">逾期计费</span>
          </div>

          <div class="cell-amount">
            <span class="amount">¥{{ row.amount.toFixed(2) }}</span>
          </div>

          <div class="cell-state">
            <span class="state-word">{{ row.status === 0 ? '待缴' : '已缴' }}</span>
            <span class="state-en">{{ row.status === 0 ? 'UNPAID' : 'PAID' }}</span>
            <span class="state-note">
              {{ row.status === 0 ? `开单 ${formatDate(row.createTime)}` : `结清 ${formatDate(row.paidTime)}` }}
            </span>
          </div>

          <div class="cell-ops">
            <button v-if="row.status === 0" type="button" class="act act--ink" @click="onPay(row)">
              缴费
            </button>
            <span v-else class="act-none">已结清</span>
          </div>
        </article>
      </div>

      <div class="ledger-foot">
        <span class="foot-note">罚款由服务台统一核销，缴纳后自动更新账目。</span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </section>

    <!-- 空态 -->
    <section v-else class="ledger ledger-empty">
      <span class="empty-mark" aria-hidden="true">∅</span>
      <p class="empty-title">{{ statusFilter === 0 ? '没有待缴的罚款' : statusFilter === 1 ? '暂无已缴记录' : '暂无罚款记录' }}</p>
      <span class="empty-tip">
        {{ statusFilter === 0 ? '账目已结清，或可切换分段查看历史记录' : '图书逾期归还后，罚款会出现在这里' }}
      </span>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { borrowApi, systemApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { formatDate } from '@/utils/datetime.js'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading.js'
import { useScrollRestore } from '@/composables/useScrollRestore'
import { useSnapshotRefresh } from '@/composables/useSnapshotRefresh'

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: 'BorrowFine' })

const pageRef = ref(null)
useScrollRestore(pageRef)

const userStore = useUserStore()
const isAdmin = computed(() => userStore.isAdmin)

const list = ref([])
const statusFilter = ref('')
// 逾期罚款费率（元/天），由管理员在系统设置中配置；拉取失败回退默认 0.5
const fineRate = ref(0.5)
const fineRateText = computed(() => String(parseFloat(Number(fineRate.value).toFixed(2))))

// 前端分页（后端 /fine/list 一次返回全量，结算单也需全量计算）
const page = ref(1)
const size = ref(10)

const STATUS_TABS = [
  { key: 'all', label: '全部', en: 'ALL', value: '' },
  { key: 'unpaid', label: '待缴', en: 'UNPAID', value: 0 },
  { key: 'paid', label: '已缴', en: 'PAID', value: 1 },
]

const filteredList = computed(() => {
  if (statusFilter.value === '') return list.value
  return list.value.filter((f) => f.status === statusFilter.value)
})

const total = computed(() => filteredList.value.length)
const pagedList = computed(() =>
  filteredList.value.slice((page.value - 1) * size.value, page.value * size.value)
)

const unpaidCount = computed(() => list.value.filter((f) => f.status === 0).length)
const paidCount = computed(() => list.value.length - unpaidCount.value)
const unpaidTotal = computed(() =>
  list.value.filter((f) => f.status === 0).reduce((s, f) => s + f.amount, 0)
)
const paidTotal = computed(() =>
  list.value.filter((f) => f.status === 1).reduce((s, f) => s + f.amount, 0)
)

// 分段上的计数：全部 / 待缴 / 已缴
const tabCount = (value) => (value === '' ? list.value.length : value === 0 ? unpaidCount.value : paidCount.value)

// 序号按整表偏移，翻页后仍然连续
const ordinal = (i) => String((page.value - 1) * size.value + i + 1).padStart(2, '0')

// 切换状态过滤后回到第一页，避免停在超出范围的页码
watch(statusFilter, () => {
  page.value = 1
})

// 快照含罚款列表与罚款费率：费率由系统设置页改动，同样会过期
const fetchSnapshot = async () => {
  const [data, rules] = await Promise.all([
    borrowApi.fineList(),
    systemApi.getLibraryRules().catch(() => null),
  ])
  return {
    list: data.list,
    fineRate: rules && rules.fineRatePerDay != null ? Number(rules.fineRatePerDay) : fineRate.value,
  }
}

const applySnapshot = (s) => {
  list.value = s.list
  fineRate.value = s.fineRate
}

const { reload } = useSnapshotRefresh({
  fetch: fetchSnapshot,
  apply: applySnapshot,
})

// 页面自身的加载（首屏、缴费后）带遮罩；激活比对走静默路径，不闪遮罩
const load = async () => {
  showPageLoading()
  try {
    await reload()
  } finally {
    hidePageLoading()
  }
}

const onPay = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认缴纳《${row.bookTitle}》罚款 ¥${row.amount.toFixed(2)}？`,
      '缴费确认',
      { type: 'warning' }
    )
    const res = await borrowApi.payFine({ id: row.id })
    ElMessage.success(res.message || '缴费成功')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

onMounted(load)
</script>

<style scoped>
/* ===== 页头 ===== */
.fine-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-6);
}

.head-text .desc {
  margin: 0;
}

.head-figure {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-bottom: 2px;
  border-bottom: 1px solid var(--color-border-strong);
  min-width: 96px;
}

.fig-num {
  font-family: var(--font-serif);
  font-size: 2.5rem;
  font-weight: 600;
  line-height: 1;
  letter-spacing: var(--ls-tight);
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.fig-label {
  margin-top: 6px;
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

/* ===== 结算单 ===== */
.statement {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: var(--space-5);
}

.stmt-main {
  padding: var(--space-6);
  min-width: 0;
}

.stmt-eyebrow {
  display: block;
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-3);
}

.stmt-amount {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-family: var(--font-serif);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: var(--color-danger);
}

.stmt-amount .cur {
  font-size: 1.375rem;
  font-weight: 600;
  opacity: 0.8;
}

.stmt-amount .num {
  font-size: clamp(2.5rem, 4.5vw, 3.5rem);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
}

.statement.is-clear .stmt-amount {
  color: var(--color-text-tertiary);
}

.stmt-note {
  margin: var(--space-4) 0 0;
  font-size: var(--fs-small);
  line-height: var(--lh-relaxed);
  color: var(--color-text-secondary);
}

.stmt-note b {
  font-weight: 600;
  color: var(--color-danger);
}

.stmt-side {
  display: grid;
  align-content: center;
  gap: var(--space-5);
  padding: var(--space-6);
  border-left: 1px solid var(--color-divider);
  background: var(--color-bg-subtle);
}

.stmt-metric {
  display: grid;
  gap: 4px;
}

.m-label {
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.m-value {
  font-family: var(--font-serif);
  font-size: 1.25rem;
  font-weight: 600;
  line-height: 1.2;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.m-value .unit {
  margin-left: 4px;
  font-family: var(--font-sans);
  font-size: var(--fs-caption);
  font-weight: 400;
  color: var(--color-text-tertiary);
}

.m-sub {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* ===== 计费规则 ===== */
.rules-label {
  margin-bottom: var(--space-3);
}

.rules {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
  padding: var(--space-5) 0;
  margin-bottom: var(--space-5);
}

.rule {
  display: flex;
  gap: var(--space-3);
  padding: 0 var(--space-5);
  border-left: 1px solid var(--color-divider);
}

.rule:first-child {
  border-left: none;
  padding-left: 0;
}

.rule-no {
  font-family: var(--font-serif);
  font-size: 1.25rem;
  font-weight: 600;
  line-height: 1.1;
  color: var(--color-primary);
  opacity: 0.5;
  flex-shrink: 0;
}

.rule-title {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 4px;
}

.rule-desc {
  margin: 0;
  font-size: var(--fs-caption);
  line-height: 1.75;
  color: var(--color-text-secondary);
}

.rule-desc b {
  color: var(--color-warning);
  font-weight: 600;
}

/* ===== 状态分段 ===== */
.band {
  display: flex;
  gap: var(--space-1);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-4);
  overflow-x: auto;
  scrollbar-width: none;
}

.band::-webkit-scrollbar {
  display: none;
}

.band-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex: 0 0 auto;
  padding: var(--space-3) var(--space-4) calc(var(--space-3) + 2px);
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  cursor: pointer;
  font-family: inherit;
  color: var(--color-text-tertiary);
  transition: color var(--duration-fast) var(--ease-soft),
    border-color var(--duration-fast) var(--ease-soft);
}

.band-item:hover {
  color: var(--color-text);
}

.band-item.active {
  color: var(--color-text);
  border-bottom-color: var(--color-text);
}

.band-label {
  font-size: var(--fs-small);
  font-weight: 500;
}

.band-en {
  font-size: 9px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  opacity: 0.7;
}

.band-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

/* ===== 罚款账册 ===== */
.ledger {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.ledger-headrow,
.entry {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 116px 132px 156px 92px;
  align-items: center;
  gap: var(--space-4);
}

.ledger-headrow {
  padding: var(--space-3) var(--space-5) var(--space-3) var(--space-4);
  background: var(--color-bg-subtle);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-tertiary);
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
}

.ledger-headrow .col-ops {
  text-align: right;
}

.entry {
  position: relative;
  padding: var(--space-4) var(--space-5) var(--space-4) var(--space-4);
  border-bottom: 1px solid var(--color-divider);
  transition: background-color var(--duration-fast) var(--ease-soft);
  animation: entry-in 0.45s var(--ease-out) both;
  animation-delay: calc(var(--i, 0) * 35ms);
}

.entry:last-child {
  border-bottom: none;
}

.entry::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--state-color, var(--color-border-strong));
  opacity: 0.55;
  transition: opacity var(--duration-fast) var(--ease-soft);
}

.entry:hover {
  background: var(--color-bg-subtle);
}

.entry:hover::before {
  opacity: 1;
}

.entry[data-state='unpaid'] {
  --state-color: var(--color-danger);
}

.entry[data-state='paid'] {
  --state-color: var(--color-secondary);
}

@keyframes entry-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.ordinal {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.cell-book {
  min-width: 0;
}

.book-title {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  font-weight: 600;
  line-height: var(--lh-snug);
  color: var(--color-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}

.book-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: 4px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  min-width: 0;
}

.meta-user {
  color: var(--color-text-secondary);
}

.meta-no {
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cell-days {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 2px;
}

.days-num {
  font-family: var(--font-serif);
  font-size: 1.0625rem;
  font-weight: 600;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.days-unit {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.days-note {
  flex-basis: 100%;
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--color-text-tertiary);
}

.amount {
  font-family: var(--font-serif);
  font-size: 1.125rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text);
}

.entry[data-state='unpaid'] .amount {
  color: var(--color-danger);
}

.entry[data-state='paid'] .amount {
  color: var(--color-text-tertiary);
}

.cell-state {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.3;
}

.state-word {
  font-size: var(--fs-small);
  font-weight: 600;
}

.state-en {
  font-size: 9px;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.state-note {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.entry[data-state='unpaid'] .state-word {
  color: var(--color-danger);
}

.entry[data-state='paid'] .state-word {
  color: var(--color-success);
}

.cell-ops {
  display: flex;
  justify-content: flex-end;
}

.act {
  background: none;
  border: none;
  padding: 2px 0;
  font-family: inherit;
  font-size: var(--fs-caption);
  letter-spacing: 0.02em;
  color: var(--color-text-secondary);
  cursor: pointer;
  border-bottom: 1px solid transparent;
  transition: color var(--duration-fast) var(--ease-soft),
    border-color var(--duration-fast) var(--ease-soft);
}

.act:hover {
  color: var(--color-text);
  border-bottom-color: currentColor;
}

.act--ink {
  color: var(--color-primary);
}

.act--ink:hover {
  color: var(--color-primary-hover);
}

.act-none {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.ledger-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--color-divider);
}

.foot-note {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* ===== 空态 ===== */
.ledger-empty {
  align-items: center;
  justify-content: center;
  padding: var(--space-8) var(--space-5);
  text-align: center;
}

.empty-mark {
  font-size: 38px;
  line-height: 1;
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-3);
}

.empty-title {
  margin: 0 0 var(--space-2);
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
}

.empty-tip {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .rules {
    grid-template-columns: 1fr;
    gap: var(--space-4);
    padding: var(--space-4) 0;
  }

  .rule {
    border-left: none;
    padding: 0;
  }

  .ledger-headrow,
  .entry {
    grid-template-columns: 44px minmax(0, 1fr) 80px 96px 108px 68px;
    gap: var(--space-3);
  }

  .days-note {
    display: none;
  }
}

@media (max-width: 960px) {
  .fine-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-4);
  }

  .head-figure {
    align-items: flex-start;
  }

  .statement {
    grid-template-columns: 1fr;
  }

  .stmt-main,
  .stmt-side {
    padding: var(--space-5);
  }

  .stmt-side {
    grid-template-columns: 1fr 1fr;
    border-left: none;
    border-top: 1px solid var(--color-divider);
  }

  .ledger-headrow {
    display: none;
  }

  .entry {
    grid-template-columns: 36px minmax(0, 1fr) auto;
    grid-template-areas:
      'no book amount'
      'no days amount'
      'no state ops';
    align-items: start;
    row-gap: var(--space-2);
  }

  .ordinal { grid-area: no; padding-top: 2px; }
  .cell-book { grid-area: book; }
  .cell-days { grid-area: days; }
  .cell-amount { grid-area: amount; text-align: right; }
  .cell-ops { grid-area: ops; align-self: end; }

  .cell-state {
    grid-area: state;
    flex-direction: row;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 2px var(--space-2);
  }

  .state-note {
    flex-basis: 100%;
  }

  .ledger-foot {
    justify-content: center;
  }

  .foot-note {
    display: none;
  }
}
</style>
