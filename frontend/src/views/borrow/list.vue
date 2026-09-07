<template>
  <div class="page borrow-page">
    <header class="page-header">
      <span class="eyebrow">借阅 · CIRCULATION</span>
      <h1>借阅记录</h1>
      <p class="desc">追踪每一本流转中的图书。</p>
    </header>

    <div class="data-table fade-in" v-if="list.length || total">
      <div class="table-toolbar">
        <div class="left">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable @change="load" style="width: 140px">
            <el-option label="待取书" :value="0" />
            <el-option label="借阅中" :value="1" />
            <el-option label="已归还" :value="2" />
            <el-option label="已逾期" :value="3" />
            <el-option label="已取消" :value="4" />
          </el-select>
          <el-input v-model="search" placeholder="借阅人 / 单号" :prefix-icon="Search" clearable class="search-input" @keyup.enter="load" />
          <el-button @click="load">搜索</el-button>
        </div>
        <div class="right">
          <el-button v-if="userStore.isAdmin" type="primary" :icon="Check" :disabled="selectedRows.length === 0" @click="onBatchPickup">批量取书({{ selectedRows.length }})</el-button>
          <el-button v-if="userStore.isAdmin" :icon="Check" @click="onPickupByCode">手动取书</el-button>
          <el-button type="success" :icon="Check" :disabled="returnRows.length === 0" @click="onBatchReturn">批量归还({{ returnRows.length }})</el-button>
        </div>
      </div>

      <el-table :data="list" style="width: 100%" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="48" :selectable="(row) => row.status === 0 || row.status === 1 || row.status === 3" />
        <el-table-column prop="borrowNo" label="借阅单号" width="180">
          <template #default="{ row }">
            <span class="mono-text">{{ row.borrowNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="借阅人" width="120" />
        <el-table-column prop="bookTitle" label="图书" min-width="160" />
        <el-table-column label="借阅时间" width="160">
          <template #default="{ row }">
            <span class="time-text">{{ row.borrowTime || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应还时间" width="160">
          <template #default="{ row }">
            <span class="time-text" :class="{ danger: row.status === 3 }">{{ row.dueTime || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="renewCount" label="续借" width="80">
          <template #default="{ row }">
            <span>{{ row.renewCount }}/{{ row.renewMaxCount || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-tag" :class="statusClass(row.status)">
              {{ statusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" v-if="row.status === 0" @click="openCode(row)">借阅码</el-button>
            <el-button text type="primary" size="small" v-if="row.status === 0 && userStore.isAdmin" @click="onPickup(row)">取书确认</el-button>
            <el-button text type="primary" size="small" :disabled="row.status !== 1" @click="onRenew(row)">续借</el-button>
            <el-button text type="primary" size="small" :disabled="row.status !== 1 && row.status !== 3" @click="onReturn(row)">归还</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 借阅码弹窗 -->
    <BorrowCodeDialog
      v-model="codeVisible"
      :borrowNo="codeData.borrowNo"
      :pickupDeadline="codeData.pickupDeadline"
      :bookTitle="codeData.bookTitle"
      :qrPayload="codeData.qrPayload"
      :showPickup="userStore.isAdmin && codeData.status === 0"
      :pickupLoading="pickupLoading"
      mode="view"
      @pickup="onPickupFromCode"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search, Check } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { borrowApi } from '@/api'
import { useUserStore } from '@/stores/user'
import BorrowCodeDialog from '@/components/BorrowCodeDialog.vue'

const userStore = useUserStore()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const search = ref('')
const statusFilter = ref('')

const codeVisible = ref(false)
const codeData = ref({ borrowNo: '', pickupDeadline: '', bookTitle: '', qrPayload: '', status: null })
const selectedRows = ref([])
const pickupLoading = ref(false)

// 可归还的选中行（借阅中/已逾期）
const returnRows = computed(() => selectedRows.value.filter((r) => r.status === 1 || r.status === 3))

const load = async () => {
  const data = await borrowApi.list({ page: page.value, size: size.value, status: statusFilter.value, search: search.value })
  list.value = data.list
  total.value = data.total
}

const onSizeChange = () => {
  page.value = 1
  load()
}

const statusText = (s) => ({ 0: '待取书', 1: '借阅中', 2: '已归还', 3: '已逾期', 4: '已取消' }[s] || '')
const statusClass = (s) => ({ 0: 'pending', 1: 'active', 2: 'returned', 3: 'overdue', 4: 'cancelled' }[s] || '')

const openCode = (row) => {
  codeData.value = {
    borrowNo: row.borrowNo,
    pickupDeadline: row.pickupDeadline,
    bookTitle: row.bookTitle,
    qrPayload: row.qrPayload,
    status: row.status,
  }
  codeVisible.value = true
}

const onPickupFromCode = async (borrowNo) => {
  pickupLoading.value = true
  try {
    const res = await borrowApi.pickup({ borrowNo })
    ElMessage.success(res.message || '取书成功')
    codeVisible.value = false
    load()
  } catch {
    // 错误已由拦截器提示
  } finally {
    pickupLoading.value = false
  }
}

const onSelectionChange = (rows) => {
  selectedRows.value = rows
}

const onPickup = async (row) => {
  try {
    await ElMessageBox.confirm(`确认「${row.userName}」已到馆取书《${row.bookTitle}》？`, '取书确认', {
      type: 'info',
    })
    const res = await borrowApi.pickup({ borrowNo: row.borrowNo })
    ElMessage.success(res.message || '取书成功')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onBatchPickup = async () => {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(`确认批量取书 ${rows.length} 条记录？`, '批量取书确认', {
      type: 'info',
    })
    const res = await borrowApi.pickup({ borrowNos: rows.map((r) => r.borrowNo) })
    ElMessage.success(res.message || `成功 ${res.successCount} 条`)
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onPickupByCode = async () => {
  try {
    const { value } = await ElMessageBox.prompt('请输入借阅码', '扫码取书', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputPlaceholder: 'BR...',
    })
    if (!value) return
    const res = await borrowApi.pickup({ borrowNo: value.trim() })
    ElMessage.success(res.message || '取书成功')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onReturn = async (row) => {
  try {
    await ElMessageBox.confirm(`确认归还《${row.bookTitle}》？`, '归还确认', { type: 'info' })
    await borrowApi.return({ id: row.id })
    ElMessage.success('归还成功')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onBatchReturn = async () => {
  const rows = returnRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(`确认批量归还 ${rows.length} 条借阅记录？`, '批量归还确认', { type: 'info' })
    const res = await borrowApi.batchReturn({ ids: rows.map((r) => r.id) })
    ElMessage.success(`成功归还 ${res.successCount} 条`)
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onRenew = async (row) => {
  try {
    await borrowApi.renew({ id: row.id })
    ElMessage.success('续借成功')
    load()
  } catch {
    // 错误已由拦截器提示
  }
}

onMounted(load)
</script>

<style scoped>
.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}
.time-text {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
.time-text.danger { color: var(--color-danger); }
</style>
