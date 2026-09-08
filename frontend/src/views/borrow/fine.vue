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

    <div class="data-table">
      <div class="table-toolbar">
        <div class="left">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="未缴" :value="0" />
            <el-option label="已缴" :value="1" />
          </el-select>
        </div>
      </div>

      <el-table :data="filteredList" style="width: 100%">
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
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { borrowApi } from '@/api'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading.js'

const list = ref([])
const statusFilter = ref('')

const filteredList = computed(() => {
  if (statusFilter.value === '') return list.value
  return list.value.filter((f) => f.status === statusFilter.value)
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
    const data = await borrowApi.fineList()
    list.value = data.list
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

.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.amount {
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-body);
}
.amount.danger { color: var(--color-danger); }
.amount.success { color: var(--color-success); }
</style>
