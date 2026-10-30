<template>
  <div class="page borrow-page" ref="pageRef">
    <!-- 页头：左侧标题，右侧记录数 -->
    <header class="page-header ledger-head">
      <div class="head-text">
        <span class="eyebrow">借阅 · CIRCULATION</span>
        <h1>借阅记录</h1>
        <p class="desc">追踪每一本流转中的图书。</p>
      </div>
      <div class="head-figure" aria-label="当前筛选记录数">
        <span class="fig-num">{{ total }}</span>
        <span class="fig-label">{{ statusFilter === '' ? '全部记录' : statusText(statusFilter) }}</span>
      </div>
    </header>

    <!-- 状态带：按流通进度排列，点选即筛选 -->
    <nav class="status-band" aria-label="按状态筛选">
      <button
        v-for="(s, bi) in STATUS_TABS"
        :key="s.key"
        :ref="(el) => (bandItemEls[bi] = el)"
        type="button"
        class="band-item"
        :class="{ active: statusFilter === s.value }"
        :aria-pressed="statusFilter === s.value"
        @click="selectStatus(s.value)"
      >
        <span class="band-mark" :data-state="s.key" aria-hidden="true"></span>
        <span class="band-label">{{ s.label }}</span>
        <span class="band-en">{{ s.en }}</span>
        <span v-if="statusFilter === s.value" class="band-count">{{ total }}</span>
      </button>
      <!-- 滑动指示条：随激活分段平移 -->
      <span class="band-ink" :class="{ ready: inkReady }" :style="inkStyle" aria-hidden="true"></span>
    </nav>

    <!-- 工具行：检索 + 管理员扫码入口 -->
    <div class="ledger-toolbar">
      <div class="tb-left">
        <el-input
          v-model="search"
          placeholder="书名 / 借阅人 / 借阅单号"
          :prefix-icon="Search"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-button @click="onSearch">检索</el-button>
      </div>
      <div class="tb-right" v-if="userStore.isAdmin">
        <el-button :icon="Tickets" @click="onProcessByCode">扫码办理</el-button>
      </div>
    </div>

    <!-- 勾选汇总条：有选中才出现，批量动作收在这里 -->
    <transition name="slip">
      <div v-if="userStore.isAdmin && selectedRows.length" class="selection-slip">
        <div class="slip-info">
          <span class="slip-count">已选 <b>{{ selectedRows.length }}</b> 条</span>
          <span v-if="selectedOverdue.length" class="slip-overdue">含逾期 {{ selectedOverdue.length }} 条</span>
          <span class="slip-hint">柜台办理会按每条当前状态自动分流</span>
        </div>
        <div class="slip-actions">
          <button type="button" class="act" @click="clearSelection">取消选择</button>
          <el-button
            type="warning"
            plain
            size="small"
            :icon="Bell"
            :disabled="selectedOverdue.length === 0"
            @click="onBatchUrge"
          >批量催还</el-button>
          <el-button type="primary" size="small" :icon="Checked" @click="processVisible = true">
            批量办理
          </el-button>
        </div>
      </div>
    </transition>

    <!-- 借阅账册 -->
    <section v-if="list.length" class="ledger" :class="{ settled }" aria-label="借阅记录列表">
      <div class="ledger-headrow">
        <div class="cell-select">
          <label v-if="userStore.isAdmin && selectableRows.length" class="pick">
            <input
              type="checkbox"
              :checked="allSelected"
              :indeterminate="someSelected"
              aria-label="全选当前页可办理记录"
              @change="toggleAll"
            />
            <span class="pick-box" aria-hidden="true"></span>
          </label>
          <span class="col-no">NO.</span>
        </div>
        <span class="col-book">图书 / 借阅人</span>
        <span class="col-flow">流通时间</span>
        <span class="col-state">状态</span>
        <span class="col-ops">操作</span>
      </div>

      <div class="ledger-body" ref="bodyRef">
        <article
          v-for="(row, i) in list"
          :key="row.id"
          class="entry"
          :data-state="stateKey(row.status)"
          :style="{ '--i': Math.min(i, 9) }"
        >
          <div class="cell-select">
            <label v-if="userStore.isAdmin && selectable(row)" class="pick">
              <input
                type="checkbox"
                :checked="isSelected(row)"
                :aria-label="`选择 ${row.bookTitle}`"
                @change="toggleRow(row)"
              />
              <span class="pick-box" aria-hidden="true"></span>
            </label>
            <span class="ordinal">{{ ordinal(i) }}</span>
          </div>

          <div class="cell-book">
            <h3 class="book-title" :title="row.bookTitle">{{ row.bookTitle }}</h3>
            <div class="book-meta">
              <span v-if="userStore.isAdmin" class="book-user">{{ row.userName }}</span>
              <span class="book-no">{{ row.borrowNo }}</span>
              <span v-if="row.renewMaxCount" class="book-renew">
                续借 {{ row.renewCount ?? 0 }}/{{ row.renewMaxCount }}
              </span>
            </div>
          </div>

          <div class="cell-flow">
            <div class="flow-node">
              <span class="flow-k">{{ startNode(row).label }}</span>
              <span class="flow-v">{{ formatDate(startNode(row).time) }}</span>
            </div>
            <div v-if="endNode(row)" class="flow-node">
              <span class="flow-k">{{ endNode(row).label }}</span>
              <span class="flow-v" :class="{ 'is-danger': row.status === 3 }">
                {{ formatDate(endNode(row).time) }}
              </span>
            </div>
            <span v-else class="flow-void">未取书即作废</span>
          </div>

          <div class="cell-state">
            <span class="state-word">{{ statusText(row.status) }}</span>
            <span class="state-en">{{ statusEn(row.status) }}</span>
            <span class="state-note">{{ stateNote(row) }}</span>
          </div>

          <div class="cell-ops">
            <template v-if="row.status === 0">
              <button type="button" class="act" @click="openCode(row)">借阅码</button>
              <button v-if="userStore.isAdmin" type="button" class="act act--ink" @click="onPickup(row)">
                取书确认
              </button>
            </template>
            <template v-else-if="row.status === 1">
              <button type="button" class="act" @click="onRenew(row)">续借</button>
              <button type="button" class="act act--ink" @click="onReturn(row)">
                {{ userStore.isAdmin ? '归还' : '申请归还' }}
              </button>
            </template>
            <template v-else-if="row.status === 3">
              <button v-if="userStore.isAdmin" type="button" class="act act--warn" @click="onUrge(row)">
                催还
              </button>
              <button type="button" class="act act--ink" @click="onReturn(row)">
                {{ userStore.isAdmin ? '归还' : '申请归还' }}
              </button>
            </template>
            <template v-else-if="row.status === 5 && userStore.isAdmin">
              <button type="button" class="act act--ok" @click="onConfirmReturn(row)">归还确认</button>
              <button type="button" class="act act--danger" @click="onRejectReturn(row)">驳回</button>
            </template>
            <span v-else-if="row.status === 5" class="act-none">待柜台核对</span>
            <span v-else class="act-none">—</span>
          </div>
        </article>
      </div>
    </section>

    <!-- 空态 -->
    <section v-else class="ledger ledger-empty">
      <span class="empty-mark">∅</span>
      <p class="empty-title">
        {{ statusFilter !== '' ? '该状态下暂无借阅记录' : search ? '未找到匹配的借阅记录' : '暂无借阅记录' }}
      </p>
      <span class="empty-tip">
        {{ statusFilter !== '' ? '换个状态看看，或清除筛选查看全部记录' : search ? '试试更换关键词，或清除搜索条件' : '读者提交借阅申请后，记录会出现在这里' }}
      </span>
      <el-button v-if="statusFilter !== '' || search" plain size="small" @click="clearAllFilter">
        清除筛选条件
      </el-button>
    </section>

    <!-- 账册页脚 -->
    <footer v-if="list.length || total > 0" class="ledger-foot">
      <div class="foot-meta">
        <span>共 <b>{{ total }}</b> 条</span>
        <span class="foot-divider" aria-hidden="true"></span>
        <span class="foot-scope">
          {{ statusFilter === '' ? '全部状态' : statusText(statusFilter) }}{{ search ? ` · 关键词「${search}」` : '' }}
        </span>
      </div>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="sizes, prev, pager, next"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </footer>

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

    <!-- 柜台批量办理弹窗（取书 / 归还 / 归还确认统一事务） -->
    <BorrowProcessDialog v-model="processVisible" :records="selectedRows" @completed="load" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated, onBeforeUnmount, nextTick } from 'vue'
import { Search, Tickets, Checked, Bell } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { borrowApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { formatDate } from '@/utils/datetime.js'
import BorrowCodeDialog from '@/components/BorrowCodeDialog.vue'
import BorrowProcessDialog from '@/components/BorrowProcessDialog.vue'
import { useScrollRestore } from '@/composables/useScrollRestore'
import { useSnapshotRefresh } from '@/composables/useSnapshotRefresh'

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: 'BorrowList' })

const pageRef = ref(null)
const bodyRef = ref(null)
useScrollRestore(pageRef)

const userStore = useUserStore()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const search = ref('')
const statusFilter = ref('')

const codeVisible = ref(false)
const codeData = ref({ borrowNo: '', pickupDeadline: '', bookTitle: '', qrPayload: '', status: null })
const selectedRows = ref([])
const selectedOverdue = computed(() => selectedRows.value.filter((r) => r.status === 3))
const pickupLoading = ref(false)
const processVisible = ref(false)

/* ---------- 状态元信息：状态带 / 行 / 说明共用一处定义 ---------- */
const STATUS_TABS = [
  { key: 'all', value: '', label: '全部', en: 'ALL' },
  { key: 'pending', value: 0, label: '待取书', en: 'WAITING' },
  { key: 'active', value: 1, label: '借阅中', en: 'ON LOAN' },
  { key: 'overdue', value: 3, label: '已逾期', en: 'OVERDUE' },
  { key: 'confirm', value: 5, label: '待确认归还', en: 'TO CONFIRM' },
  { key: 'returned', value: 2, label: '已归还', en: 'RETURNED' },
  { key: 'cancelled', value: 4, label: '已取消', en: 'CANCELLED' },
]

const STATUS_TEXT = { 0: '待取书', 1: '借阅中', 2: '已归还', 3: '已逾期', 4: '已取消', 5: '待确认归还' }
const STATUS_EN = { 0: 'WAITING', 1: 'ON LOAN', 2: 'RETURNED', 3: 'OVERDUE', 4: 'CANCELLED', 5: 'TO CONFIRM' }
const STATUS_KEY = { 0: 'pending', 1: 'active', 2: 'returned', 3: 'overdue', 4: 'cancelled', 5: 'confirm' }

const statusText = (s) => STATUS_TEXT[s] || ''
const statusEn = (s) => STATUS_EN[s] || ''
const stateKey = (s) => STATUS_KEY[s] || 'cancelled'

/* ---------- 日期口径：只比较自然日 ---------- */
const parseDay = (t) => {
  if (!t) return null
  const [y, m, d] = String(t).split('T')[0].split('-').map(Number)
  return new Date(y, m - 1, d)
}
const daysFromToday = (t) => {
  const target = parseDay(t)
  if (!target) return null
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((target - today) / 86400000)
}

/* ---------- 单条记录的流通过程与一句状态说明 ---------- */
const startNode = (row) => {
  if (row.status === 0 || row.status === 4) return { label: '申请', time: row.applyTime }
  return { label: '借出', time: row.borrowTime }
}
const endNode = (row) => {
  if (row.status === 0) return { label: '取书截止', time: row.pickupDeadline }
  if (row.status === 2) return { label: '归还于', time: row.returnTime }
  if (row.status === 5) return { label: '归还申请', time: row.returnRequestTime }
  if (row.status === 4) return null
  return { label: '应还', time: row.dueTime }
}
const stateNote = (row) => {
  const s = row.status
  if (s === 0) {
    const left = daysFromToday(row.pickupDeadline)
    if (left == null) return '等待到馆取书'
    if (left < 0) return `已超期 ${-left} 天未取`
    if (left === 0) return '今日取书截止'
    return `剩 ${left} 天取书`
  }
  if (s === 1) {
    const left = daysFromToday(row.dueTime)
    if (left == null) return '借阅中'
    if (left < 0) return `已超期 ${-left} 天`
    if (left === 0) return '今日应还'
    return `剩 ${left} 天到期`
  }
  if (s === 3) {
    const over = daysFromToday(row.dueTime)
    return over == null ? '已逾期' : `逾期 ${Math.max(1, -over)} 天`
  }
  if (s === 2) return row.returnTime ? `${formatDate(row.returnTime)} 回库` : '已回库'
  if (s === 5) return row.returnRequestTime ? `${formatDate(row.returnRequestTime)} 提交` : '等待柜台核对'
  return '申请未取书即作废'
}

/* ---------- 选择：管理员仅在可办理状态上勾选 ---------- */
const SELECTABLE = [0, 1, 3, 5]
const selectable = (row) => SELECTABLE.includes(row.status)
const selectableRows = computed(() => list.value.filter(selectable))
const isSelected = (row) => selectedRows.value.some((r) => r.id === row.id)
const allSelected = computed(
  () => selectableRows.value.length > 0 && selectableRows.value.every(isSelected)
)
const someSelected = computed(
  () => selectableRows.value.some(isSelected) && !allSelected.value
)

const toggleRow = (row) => {
  if (isSelected(row)) {
    selectedRows.value = selectedRows.value.filter((r) => r.id !== row.id)
  } else {
    selectedRows.value = [...selectedRows.value, row]
  }
}
const toggleAll = () => {
  selectedRows.value = allSelected.value ? [] : [...selectableRows.value]
}
const clearSelection = () => {
  selectedRows.value = []
}

/* ---------- 取数与刷新 ---------- */
const fetchSnapshot = async () => {
  const data = await borrowApi.list({
    page: page.value,
    size: size.value,
    status: statusFilter.value,
    search: search.value,
  })
  return { list: data.list, total: data.total }
}

const applySnapshot = (s) => {
  list.value = s.list
  total.value = s.total
  // 刷新后把已不在当前列表中的旧行剔出选择；办理结果弹窗打开期间保留原始记录，
  // 否则「办理结果」阶段会因为选中行被清空而丢失明细
  if (!processVisible.value) {
    const ids = new Set(s.list.map((r) => r.id))
    selectedRows.value = selectedRows.value.filter((r) => ids.has(r.id))
  }
}

const { reload: load } = useSnapshotRefresh({
  fetch: fetchSnapshot,
  apply: applySnapshot,
})

// 账册在固定高度内滚动：翻页 / 换状态 / 搜索后把数据区带回顶部，
// 避免新一页直接从中间开始读
const resetBodyScroll = () => {
  if (bodyRef.value) bodyRef.value.scrollTop = 0
}

const onPageChange = () => {
  resetBodyScroll()
  load()
}

const onSearch = () => {
  clearSelection()
  resetBodyScroll()
  page.value = 1
  load()
}

const onSizeChange = () => {
  clearSelection()
  resetBodyScroll()
  page.value = 1
  load()
}

const selectStatus = (value) => {
  if (statusFilter.value === value) return
  statusFilter.value = value
  // 激活条平移到新分段（active 时 band-count 出现会改变分段宽度，等渲染完再测）
  nextTick(moveInk)
  clearSelection()
  resetBodyScroll()
  page.value = 1
  load()
}

const clearAllFilter = () => {
  statusFilter.value = ''
  search.value = ''
  clearSelection()
  resetBodyScroll()
  page.value = 1
  load()
}

const ordinal = (i) => String((page.value - 1) * size.value + i + 1).padStart(3, '0')

/* ---------- 行内动作 ---------- */
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

// 扫码办理：按记录状态自动分流（待取书→取书；借阅中/逾期→归还；待确认→归还确认）
const onProcessByCode = async () => {
  try {
    const { value } = await ElMessageBox.prompt(
      '扫描或输入借阅码，系统将按记录状态自动办理（取书 / 归还 / 归还确认）',
      '扫码办理',
      {
        confirmButtonText: '办理',
        cancelButtonText: '取消',
        inputPlaceholder: 'BR...',
        inputPattern: /\S+/,
        inputErrorMessage: '请输入借阅码',
      }
    )
    const no = value.trim()
    const res = await borrowApi.batchProcess({ borrowNos: [no] })
    const item = res.results?.[0]
    if (!item?.success) {
      ElMessage.error(item?.message || '办理失败')
      return
    }
    const text = { pickup: '取书成功', return: '归还成功', confirm: '归还确认成功' }[item.action] || '办理成功'
    ElMessage.success(`${text}：《${item.bookTitle || no}》· ${item.userName || ''}`)
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onReturn = async (row) => {
  try {
    if (userStore.isAdmin) {
      // 管理员：柜台现场收书，直接完成归还
      await ElMessageBox.confirm(`确认已收到「${row.userName}」归还的《${row.bookTitle}》？`, '归还确认', { type: 'info' })
    } else {
      // 用户：发起归还申请，待管理员核对实物
      await ElMessageBox.confirm(
        '将发起归还申请，请随后将图书送至服务台，由管理员核对并确认后完成归还。逾期罚款按申请时间结算。',
        '申请归还',
        { type: 'info', confirmButtonText: '提交申请', cancelButtonText: '取消' }
      )
    }
    await borrowApi.return({ id: row.id })
    ElMessage.success(userStore.isAdmin ? '归还成功' : '归还申请已提交，请送书至服务台')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

// 管理员：确认收到待归还图书（5 → 2，库存 +1）
const onConfirmReturn = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认已收到「${row.userName}」归还的《${row.bookTitle}》？确认后库存将增加，逾期罚款按申请时间结算。`,
      '归还确认',
      { type: 'info', confirmButtonText: '确认收书', cancelButtonText: '取消' }
    )
    await borrowApi.confirmReturn({ id: row.id })
    ElMessage.success('归还确认成功')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

// 管理员：驳回归还申请（5 → 1/3，逾期继续计算）
const onRejectReturn = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `驳回归还申请后，「${row.userName}」的《${row.bookTitle}》将恢复为借阅中/已逾期状态，额度继续占用。`,
      '驳回归还申请',
      {
        type: 'warning',
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（选填，如 图书缺失/污损）',
      }
    )
    await borrowApi.rejectReturn({ id: row.id, reason: value?.trim() || '' })
    ElMessage.success('已驳回归还申请')
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

// 单条催还：向逾期读者下发站内信 + 邮件催还通知
const onUrge = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认向「${row.userName}」下发《${row.bookTitle}》的催还通知（站内信 + 邮件）？`,
      '催还通知',
      { type: 'warning', confirmButtonText: '下发催还', cancelButtonText: '取消' }
    )
    const res = await borrowApi.urge({ borrowNos: [row.borrowNo] })
    ElMessage.success(`催还通知已下发：成功 ${res.success} 条，跳过 ${res.skipped} 条（站内信即时送达，邮件在后台投递，稍后可在「系统设置 → 通知管理」查看结果）`)
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

// 批量催还：对勾选中的逾期记录统一下发催还通知
const onBatchUrge = async () => {
  const overdue = selectedOverdue.value
  if (!overdue.length) {
    ElMessage.warning('请先勾选逾期的借阅记录')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认向选中的 ${overdue.length} 条逾期记录下发催还通知？`,
      '批量催还',
      { type: 'warning', confirmButtonText: '批量催还', cancelButtonText: '取消' }
    )
    const res = await borrowApi.urge({ borrowNos: overdue.map((r) => r.borrowNo) })
    ElMessage.success(`催还通知已下发：成功 ${res.success} 条，跳过 ${res.skipped} 条（站内信即时送达，邮件在后台投递，稍后可在「系统设置 → 通知管理」查看结果）`)
    load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

/* ---------- 状态带滑动指示条 ---------- */
const bandItemEls = []
const inkStyle = reactive({ transform: 'translateX(0px)', width: '0px' })
const inkReady = ref(false)

const moveInk = () => {
  const idx = STATUS_TABS.findIndex((s) => s.value === statusFilter.value)
  const el = bandItemEls[idx]
  if (!el) return
  inkStyle.transform = `translateX(${el.offsetLeft}px)`
  inkStyle.width = `${el.offsetWidth}px`
}

// 首帧先无过渡定位到当前分段，下一帧再开过渡，避免初始从 0 滑过去
const onWindowResize = () => moveInk()
onMounted(() => {
  load()
  requestAnimationFrame(() => {
    moveInk()
    requestAnimationFrame(() => { inkReady.value = true })
  })
  window.addEventListener('resize', onWindowResize)
  // 分段宽度受字体影响，字体就绪后再校准一次
  document.fonts?.ready?.then(moveInk)
})
onActivated(() => nextTick(moveInk))
onBeforeUnmount(() => window.removeEventListener('resize', onWindowResize))

// 入场动画只在首屏播一次；此后翻页/切分段/快照刷新都是数据就地重渲染，不整墙重播
const settled = ref(false)
setTimeout(() => { settled.value = true }, 1000)
</script>

<style scoped>
/* ===== 页面骨架 =====
 * 与全站列表页同一套方案：页面保持自然文档流，仅给记录滚动区限高，
 * 数据多时表体内部滚动，页头 / 状态带 / 分页始终可见，不会出现双滚动条。 */

/* ===== 页头 ===== */
.ledger-head {
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

/* ===== 状态带 ===== */
.status-band {
  position: relative;
  display: flex;
  gap: var(--space-1);
  overflow-x: auto;
  padding-bottom: 0;
  margin-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
  scrollbar-width: none;
}

.status-band::-webkit-scrollbar {
  display: none;
}

.band-item {
  position: relative;
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
}

/* 滑动指示条：替代逐项 border，随激活分段平移 */
.band-ink {
  position: absolute;
  left: 0;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--color-text);
  pointer-events: none;
}

.band-ink.ready {
  transition: transform var(--duration) var(--ease-soft),
    width var(--duration) var(--ease-soft);
}

.band-mark {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  flex: 0 0 auto;
  align-self: center;
  transition: transform var(--duration) var(--ease-out);
}

.band-item.active .band-mark {
  transform: scale(1.35);
}

.band-mark[data-state='pending'] { background: var(--color-warning); }
.band-mark[data-state='active'] { background: var(--color-primary); }
.band-mark[data-state='confirm'] { background: var(--color-info); }
.band-mark[data-state='overdue'] { background: var(--color-danger); }
.band-mark[data-state='returned'] { background: var(--color-secondary); }
.band-mark[data-state='cancelled'] { background: var(--color-border-strong); }

.band-label {
  font-size: var(--fs-small);
  font-weight: 500;
  letter-spacing: 0.02em;
}

.band-en {
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  opacity: 0.55;
}

.band-count {
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  font-weight: 600;
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
}

/* ===== 工具行 ===== */
.ledger-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.tb-left {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: 1;
  min-width: 240px;
}

.tb-left .search-input {
  max-width: 320px;
}

/* ===== 勾选汇总条 ===== */
.selection-slip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-4);
  background: var(--color-primary-light);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius);
}

.slip-info {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.slip-count b {
  font-family: var(--font-serif);
  font-size: 1.125rem;
  color: var(--color-primary);
  margin: 0 2px;
}

.slip-overdue {
  color: var(--color-danger);
  font-weight: 500;
}

.slip-hint {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.slip-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: 0 0 auto;
}

/* ===== 账册 ===== */
.ledger {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.ledger-headrow {
  flex: 0 0 auto;
}

.ledger-body {
  overflow-y: auto;
  overscroll-behavior: contain;
  /* 限高内滚：header 60 + 页头/状态带/工具行/分页约 232，再留余量；
     max() 兜底保证矮屏下记录区不小于 440px（约 5 条） */
  max-height: max(440px, calc(100vh - var(--header-height) - 292px));
}

.ledger-headrow,
.entry {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 190px 150px 190px;
  grid-template-areas: 'sel book flow state ops';
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

.cell-select { grid-area: sel; display: flex; align-items: center; gap: var(--space-2); }
.cell-book { grid-area: book; }
.cell-flow { grid-area: flow; }
.cell-state { grid-area: state; }
.cell-ops { grid-area: ops; justify-self: end; }

.col-book,
.col-flow,
.col-state,
.col-ops {
  font-size: 10px;
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

/* 首屏入场动画只播一次，之后数据替换（翻页/切分段/快照刷新）不再整墙重播 */
.ledger.settled .entry {
  animation: none;
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

.entry[data-state='pending'] { --state-color: var(--color-warning); }
.entry[data-state='active'] { --state-color: var(--color-primary); }
.entry[data-state='confirm'] { --state-color: var(--color-info); }
.entry[data-state='overdue'] { --state-color: var(--color-danger); }
.entry[data-state='returned'] { --state-color: var(--color-secondary); }
.entry[data-state='cancelled'] { --state-color: var(--color-text-tertiary); }

/* 逾期：极淡斜纹底，远看是一层红雾 */
.entry[data-state='overdue'] {
  background-image: repeating-linear-gradient(
    135deg,
    transparent 0 12px,
    rgba(168, 90, 74, 0.035) 12px 13px
  );
}

.entry[data-state='overdue']::before {
  opacity: 1;
  width: 4px;
}

@keyframes entry-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.col-no,
.ordinal {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

/* 自定义勾选框：细线方框 + 墨色勾 */
.pick {
  position: relative;
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  display: inline-flex;
  cursor: pointer;
}

.pick input {
  position: absolute;
  inset: 0;
  opacity: 0;
  margin: 0;
  cursor: pointer;
}

.pick-box {
  width: 15px;
  height: 15px;
  border: 1px solid var(--color-border-strong);
  border-radius: 2px;
  background: var(--color-bg-elevated);
  transition: all var(--duration-fast) var(--ease-soft);
}

.pick-box::after {
  content: '';
  position: absolute;
  left: 5px;
  top: 2px;
  width: 4px;
  height: 8px;
  border: solid transparent;
  border-width: 0 1.5px 1.5px 0;
  transform: rotate(45deg) scale(0.8);
  transition: border-color var(--duration-fast) var(--ease-soft);
}

.pick:hover .pick-box {
  border-color: var(--color-primary);
}

.pick input:checked + .pick-box,
.pick input:indeterminate + .pick-box {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.pick input:checked + .pick-box::after {
  border-color: var(--color-text-inverse);
}

.pick input:indeterminate + .pick-box::after {
  left: 3px;
  top: 6px;
  width: 7px;
  height: 0;
  border-width: 0 0 1.5px 0;
  border-color: var(--color-text-inverse);
  transform: none;
}

/* 图书 / 借阅人 */
.book-title {
  font-family: var(--font-serif);
  font-size: 1.0625rem;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.01em;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.book-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: 3px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.book-user {
  color: var(--color-text-secondary);
}

.book-no {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.02em;
}

.book-renew {
  font-variant-numeric: tabular-nums;
}

/* 流通时间：两个标签 + 日期 */
.cell-flow {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.flow-node {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 64px;
}

.flow-k {
  font-size: 10px;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.flow-v {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
}

.flow-v.is-danger {
  color: var(--color-danger);
  font-weight: 600;
}

.flow-void {
  align-self: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-style: italic;
}

/* 状态 */
.cell-state {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.state-word {
  font-family: var(--font-serif);
  font-size: 1.0625rem;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: 0.02em;
  color: var(--color-text);
}

.entry[data-state='pending'] .state-word { color: var(--color-warning); }
.entry[data-state='active'] .state-word { color: var(--color-primary); }
.entry[data-state='confirm'] .state-word { color: var(--color-info); }
.entry[data-state='overdue'] .state-word { color: var(--color-danger); }
.entry[data-state='returned'] .state-word { color: var(--color-secondary); }
.entry[data-state='cancelled'] .state-word { color: var(--color-text-tertiary); }

.state-en {
  font-size: 9px;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
  opacity: 0.7;
}

.state-note {
  margin-top: 2px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.entry[data-state='pending'] .state-note,
.entry[data-state='overdue'] .state-note {
  color: var(--color-text-secondary);
}

.entry[data-state='overdue'] .state-note {
  color: var(--color-danger);
}

/* 操作 */
.cell-ops {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
  flex-wrap: wrap;
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

.act--ink { color: var(--color-primary); }
.act--ink:hover { color: var(--color-primary-hover); }
.act--ok { color: var(--color-success); }
.act--warn { color: var(--color-warning); }
.act--danger { color: var(--color-danger); }

.act-none {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* ===== 空态 ===== */
.ledger-empty {
  display: flex;
  flex-direction: column;
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
  display: block;
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-4);
}

/* ===== 页脚 ===== */
.ledger-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-4) 0 0;
  flex-wrap: wrap;
}

.foot-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.foot-meta b {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  color: var(--color-text);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.foot-divider {
  width: 1px;
  height: 12px;
  background: var(--color-border-strong);
}

.foot-scope {
  color: var(--color-text-secondary);
}

/* ===== 过渡 ===== */
.slip-enter-active,
.slip-leave-active {
  transition: opacity var(--duration) var(--ease-soft),
    transform var(--duration) var(--ease-out);
}

.slip-enter-from,
.slip-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ===== 响应式 ===== */
@media (max-width: 1180px) {
  .ledger-headrow,
  .entry {
    grid-template-columns: 56px minmax(0, 1fr) 140px;
    grid-template-areas:
      'sel book state'
      'sel flow ops';
    row-gap: var(--space-3);
  }

  .ledger-headrow {
    grid-template-areas: 'sel book state';
  }

  .ledger-headrow .col-flow,
  .ledger-headrow .col-ops {
    display: none;
  }

  .cell-ops {
    justify-self: end;
  }
}

@media (max-width: 860px) {
  .head-figure {
    min-width: 72px;
  }
  .fig-num {
    font-size: 2rem;
  }
  .band-item {
    padding: var(--space-2) var(--space-3);
  }
  .band-en {
    display: none;
  }
  .tb-left .search-input {
    max-width: none;
  }
  .ledger-toolbar {
    align-items: stretch;
  }
}

@media (max-width: 680px) {
  .ledger-head {
    align-items: flex-start;
  }
  .head-figure {
    display: none;
  }

  .ledger-headrow {
    display: none;
  }

  .entry {
    grid-template-columns: 28px minmax(0, 1fr);
    grid-template-areas:
      'sel book'
      'sel state'
      'flow flow'
      'ops ops';
    padding: var(--space-4);
    gap: var(--space-2);
  }

  .cell-select {
    align-items: flex-start;
    padding-top: 2px;
  }

  .ordinal {
    display: none;
  }

  .cell-state {
    flex-direction: row;
    align-items: baseline;
    gap: var(--space-2);
    flex-wrap: wrap;
  }

  .state-en {
    display: none;
  }

  .cell-flow {
    margin-top: var(--space-1);
    padding-top: var(--space-2);
    border-top: 1px dashed var(--color-divider);
    gap: var(--space-5);
  }

  .cell-ops {
    justify-content: flex-start;
    margin-top: var(--space-1);
    gap: var(--space-4);
  }

  .selection-slip {
    flex-direction: column;
    align-items: stretch;
  }

  .slip-actions {
    justify-content: flex-end;
  }

  .ledger-foot {
    flex-direction: column;
    align-items: stretch;
  }
}

/* 小屏取消限高内滚：屏幕本来就窄，回退为整页自然滚动 */
@media (max-width: 768px) {
  .ledger:not(.ledger-empty) {
    display: block;
  }

  .ledger-body {
    max-height: none;
    overflow: visible;
  }

  .ledger-empty {
    min-height: 260px;
  }
}

/* 矮屏压紧页头与状态带，把高度让给账册 */
@media (min-width: 769px) and (max-height: 800px) {
  .ledger-head {
    margin-bottom: var(--space-4);
  }

  .status-band {
    margin-bottom: var(--space-3);
  }

  .ledger-toolbar {
    margin-bottom: var(--space-3);
  }

  .band-item {
    padding-top: var(--space-2);
    padding-bottom: calc(var(--space-2) + 2px);
  }

  .ledger-foot {
    padding-top: var(--space-3);
  }

  /* 矮屏间距压缩后同步放宽记录区高度 */
  .ledger-body {
    max-height: max(400px, calc(100vh - var(--header-height) - 272px));
  }
}
</style>
