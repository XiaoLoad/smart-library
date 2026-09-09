<template>
  <div class="page fine-page">
    <header class="page-header">
      <span class="eyebrow">罚款 · PENALTY</span>
      <h1>罚款记录</h1>
      <p class="desc">超期未还产生的费用。</p>
    </header>

    <!-- 汇总卡片 -->
    <div class="summary-row">
      <div class="card stat-card">
        <div class="stat-label">待缴金额</div>
        <div class="stat-value danger">
          ¥{{ unpaidTotal.toFixed(2) }}
        </div>
      </div>
      <div class="card stat-card">
        <div class="stat-label">已缴金额</div>
        <div class="stat-value success">
          ¥{{ paidTotal.toFixed(2) }}
        </div>
      </div>
      <div class="card stat-card">
        <div class="stat-label">待处理笔数</div>
        <div class="stat-value">{{ unpaidCount }}</div>
      </div>
    </div>

    <!-- 馆内规则 -->
    <div class="rules-card fade-in">
      <div class="rules-grid">
        <div class="rule-item">
          <span class="rule-no">壹</span>
          <div class="rule-body">
            <div class="rule-title">逾期罚款</div>
            <p class="rule-desc">图书逾期归还，自应还日期起按 <b>{{ fineRateText }} 元 / 天</b> 计收滞纳金，归还确认时一并结算。</p>
          </div>
        </div>
        <div class="rule-item">
          <span class="rule-no">贰</span>
          <div class="rule-body">
            <div class="rule-title">丢失 / 损坏赔偿</div>
            <p class="rule-desc">图书丢失或污损，须按图书定价赔偿，成色折算以服务台现场核定为准。</p>
          </div>
        </div>
        <div class="rule-item">
          <span class="rule-no">叁</span>
          <div class="rule-body">
            <div class="rule-title">缴纳方式</div>
            <p class="rule-desc">罚款请在服务台缴清核销，未缴罚款将记录在您的账户中。</p>
          </div>
        </div>
      </div>
    </div>

    <div class="data-table">
      <div class="table-toolbar">
        <div class="left">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="未缴" :value="0" />
            <el-option label="已缴" :value="1" />
          </el-select>
        </div>
      </div>

      <el-table :data="pagedList" style="width: 100%" max-height="calc(100vh - 290px)">
        <el-table-column prop="userName" label="读者" width="120" />
        <el-table-column prop="bookTitle" label="图书" min-width="180" />
        <el-table-column prop="borrowNo" label="借阅单号" width="180">
          <template #default="{ row }">
            <span class="mono-text">{{ row.borrowNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="超期天数" width="100">
          <template #default="{ row }">
            <span>{{ row.daysOverdue }} 天</span>
          </template>
        </el-table-column>
        <el-table-column label="罚款金额" width="120">
          <template #default="{ row }">
            <span class="amount" :class="{ danger: row.status === 0, success: row.status === 1 }">
              ¥{{ row.amount.toFixed(2) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-tag" :class="row.status === 0 ? 'overdue' : 'active'">
              {{ row.status === 0 ? '未缴' : '已缴' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" text type="primary" size="small" @click="onPay(row)">缴费</el-button>
            <span v-else class="status-tag active">已缴</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { borrowApi, systemApi } from '@/api'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading.js'

const list = ref([])
const statusFilter = ref('')
// 逾期罚款费率（元/天），由管理员在系统设置中配置；拉取失败回退默认 0.5
const fineRate = ref(0.5)
const fineRateText = computed(() => String(parseFloat(Number(fineRate.value).toFixed(2))))

// 前端分页（后端 /fine/list 一次返回全量，汇总卡也需全量计算）
const page = ref(1)
const size = ref(20)

const filteredList = computed(() => {
  if (statusFilter.value === '') return list.value
  return list.value.filter((f) => f.status === statusFilter.value)
})

const total = computed(() => filteredList.value.length)
const pagedList = computed(() =>
  filteredList.value.slice((page.value - 1) * size.value, page.value * size.value)
)

// 切换状态过滤后回到第一页，避免停在超出范围的页码
watch(statusFilter, () => {
  page.value = 1
})

const unpaidTotal = computed(() =>
  list.value.filter((f) => f.status === 0).reduce((s, f) => s + f.amount, 0)
)
const paidTotal = computed(() =>
  list.value.filter((f) => f.status === 1).reduce((s, f) => s + f.amount, 0)
)
const unpaidCount = computed(() => list.value.filter((f) => f.status === 0).length)

const load = async () => {
  showPageLoading()
  try {
    const [data, rules] = await Promise.all([
      borrowApi.fineList(),
      systemApi.getLibraryRules().catch(() => null),
    ])
    list.value = data.list
    if (rules && rules.fineRatePerDay != null) fineRate.value = Number(rules.fineRatePerDay)
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
.summary-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.stat-value.danger { color: var(--color-danger); }
.stat-value.success { color: var(--color-success); }

/* —— 馆内规则卡：编辑风三栏，serif 序号 + 左色条 —— */
.rules-card {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-lg);
  padding: var(--space-4) var(--space-5);
  margin-bottom: var(--space-5);
}

.rules-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-5);
}

.rule-item {
  display: flex;
  gap: var(--space-3);
}

.rule-no {
  font-family: var(--font-serif);
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
  opacity: 0.55;
  flex-shrink: 0;
  padding-top: 2px;
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
  line-height: 1.7;
  color: var(--color-text-secondary);
}

.rule-desc b {
  color: var(--color-warning);
  font-weight: 600;
}

@media (max-width: 1024px) {
  .rules-grid {
    grid-template-columns: 1fr;
    gap: var(--space-4);
  }
}

.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

/* 表格内部滚动：细化滚动条（与馆藏图书页一致） */
:deep(.el-table .el-scrollbar__bar.is-vertical) {
  width: 6px;
  right: 2px;
}

:deep(.el-table .el-scrollbar__bar.is-horizontal) {
  height: 6px;
  bottom: 2px;
}

:deep(.el-table .el-scrollbar__bar .el-scrollbar__thumb) {
  border-radius: 999px;
}

.amount {
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-body);
}
.amount.danger { color: var(--color-danger); }
.amount.success { color: var(--color-success); }
</style>
