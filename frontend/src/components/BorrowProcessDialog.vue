<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="close"
    width="540px"
    align-center
    append-to-body
    :close-on-click-modal="phase !== 'result' && !loading"
    class="process-dialog"
  >
    <template #header>
      <div class="dlg-head">
        <span class="eyebrow">COUNTER DESK · 柜台办理</span>
        <h3 class="dlg-title">{{ phase === 'confirm' ? '流通事务办理单' : '办理结果' }}</h3>
        <div v-if="groups.length" class="summary">
          <span v-for="g in groups" :key="g.key" class="chip">
            <i class="dot" :class="g.key" />
            {{ g.label }} <b>{{ g.items.length }}</b>
          </span>
        </div>
      </div>
    </template>

    <div class="slip">
      <template v-for="g in groups" :key="g.key">
        <div class="group-head">
          <span class="g-label">{{ g.label }}</span>
          <span class="g-en">{{ g.en }}</span>
          <span class="g-rule" />
          <span class="g-count">{{ g.items.length }} 项</span>
        </div>
        <div
          v-for="(row, i) in g.items"
          :key="row.id"
          class="item"
          :style="{ animationDelay: Math.min(i, 8) * 45 + 'ms' }"
        >
          <div class="item-main">
            <span class="book">{{ row.bookTitle }}</span>
            <span class="meta">
              <span class="user">{{ row.userName }}</span>
              <span class="no">{{ row.borrowNo }}</span>
            </span>
          </div>
          <div class="item-side">
            <template v-if="phase === 'confirm'">
              <span class="transition">
                <span class="from">{{ STATUS_TEXT[row.status] }}</span>
                <el-icon class="arrow"><Right /></el-icon>
                <span class="to">{{ g.toText }}</span>
              </span>
              <span v-if="overdueInfo(row)" class="overdue">
                逾期 {{ overdueInfo(row).days }} 天 · 罚款 ¥{{ overdueInfo(row).fine }}
              </span>
            </template>
            <template v-else>
              <span v-if="resultOf(row)?.success" class="result ok">
                <el-icon><CircleCheckFilled /></el-icon>{{ actionText(resultOf(row).action) }}
              </span>
              <span v-else class="result fail" :title="resultOf(row)?.message">
                <el-icon><CircleCloseFilled /></el-icon>{{ resultOf(row)?.message || '办理失败' }}
              </span>
            </template>
          </div>
        </div>
      </template>

      <div v-if="phase === 'confirm' && anyOverdue" class="fine-note">
        <el-icon><Warning /></el-icon>
        <span>逾期罚款按{{ hasConfirm ? '归还申请时间' : '办理时间' }}以 {{ FINE_RATE }} 元/天结算，确认后自动生成罚款单</span>
      </div>
    </div>

    <template #footer>
      <div v-if="phase === 'result'" class="footer-row">
        <span class="result-summary">成功 <b>{{ successCount }}</b> · 失败 <b :class="{ bad: failCount }">{{ failCount }}</b></span>
        <el-button type="primary" @click="close">完成</el-button>
      </div>
      <div v-else class="footer-row">
        <el-button :disabled="loading" @click="close">取消</el-button>
        <el-button type="primary" :loading="loading" @click="onConfirm">确认办理（{{ total }} 项）</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Right, Warning, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { borrowApi } from '@/api'

const props = defineProps({
  modelValue: Boolean,
  /** 待办理的借阅记录（已由列表页按可选状态过滤） */
  records: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'completed'])

const STATUS_TEXT = { 0: '待取书', 1: '借阅中', 2: '已归还', 3: '已逾期', 4: '已取消', 5: '待确认归还' }
/** 与后端 FINE_RATE_PER_DAY 保持一致 */
const FINE_RATE = 0.5
const DAY_MS = 86400000

const phase = ref('confirm') // confirm 预览 | result 结果
const loading = ref(false)
const resultMap = ref({})

const groups = computed(() => {
  const defs = [
    { key: 'pickup', label: '取书', en: 'PICKUP', toText: '借阅中', match: (s) => s === 0 },
    { key: 'return', label: '归还', en: 'RETURN', toText: '已归还', match: (s) => s === 1 || s === 3 },
    { key: 'confirm', label: '归还确认', en: 'CONFIRM', toText: '已归还', match: (s) => s === 5 },
  ]
  return defs
    .map((d) => ({ ...d, items: props.records.filter((r) => d.match(r.status)) }))
    .filter((g) => g.items.length)
})

const total = computed(() => props.records.length)
const anyOverdue = computed(() => props.records.some((r) => overdueInfo(r)))
const hasConfirm = computed(() => groups.value.some((g) => g.key === 'confirm'))
const successCount = computed(() => Object.values(resultMap.value).filter((r) => r.success).length)
const failCount = computed(() => total.value - successCount.value)

// 逾期天数与罚款预估：归还确认以申请时间为准，其余按当前时间
const parseDate = (s) => {
  if (!s) return null
  const d = new Date(String(s).replace(' ', 'T'))
  return isNaN(+d) ? null : d
}
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
const overdueInfo = (row) => {
  const due = parseDate(row.dueTime)
  if (!due) return null
  const base = row.status === 5 ? parseDate(row.returnRequestTime) : null
  const eff = base ? startOfDay(base) : startOfDay(new Date())
  const days = Math.round((eff - startOfDay(due)) / DAY_MS)
  if (days <= 0) return null
  return { days, fine: (days * FINE_RATE).toFixed(2) }
}

const resultOf = (row) => resultMap.value[row.id]
const actionText = (a) => ({ pickup: '取书成功', return: '归还成功', confirm: '归还确认成功' }[a] || '办理成功')

watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      phase.value = 'confirm'
      resultMap.value = {}
    }
  }
)

const onConfirm = async () => {
  loading.value = true
  try {
    const res = await borrowApi.batchProcess({ ids: props.records.map((r) => r.id) })
    const map = {}
    for (const item of res.results || []) {
      map[item.requestId ?? item.id] = item
    }
    resultMap.value = map
    phase.value = 'result'
    emit('completed')
  } catch {
    // 错误已由拦截器提示，停留在确认页
  } finally {
    loading.value = false
  }
}

const close = () => {
  if (loading.value) return
  emit('update:modelValue', false)
}
</script>

<style scoped>
.dlg-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding-right: var(--space-6);
}

.dlg-title {
  font-size: var(--fs-h3);
  font-weight: 600;
}

.summary {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-pill);
  padding: 2px 10px;
}

.chip b {
  font-family: var(--font-mono);
  color: var(--color-text);
  font-weight: 600;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-pill);
  flex: none;
}

.dot.pickup { background: var(--color-primary); }
.dot.return { background: var(--color-success); }
.dot.confirm { background: var(--color-info); }

/* —— 办理单 · 收据式清单 —— */
.slip {
  max-height: 52vh;
  overflow-y: auto;
  padding: 0 var(--space-1);
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  padding: var(--space-4) 0 var(--space-2);
}

.group-head:first-child {
  padding-top: 0;
}

.g-label {
  font-size: var(--fs-small);
  font-weight: 600;
  color: var(--color-text);
}

.g-en {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.g-rule {
  flex: 1;
  height: 1px;
  background: var(--color-divider);
  align-self: center;
}

.g-count {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px dashed var(--color-divider);
  animation: item-in var(--duration) var(--ease-out) backwards;
}

@keyframes item-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

.item:last-of-type {
  border-bottom: none;
}

.item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.book {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  display: flex;
  gap: var(--space-2);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.no {
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
}

.item-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  flex: none;
}

.transition {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-caption);
}

.from { color: var(--color-text-tertiary); }
.arrow { font-size: 11px; color: var(--color-text-tertiary); }

.to {
  color: var(--color-primary);
  font-weight: 500;
}

.overdue {
  font-size: var(--fs-caption);
  color: var(--color-warning);
  font-family: var(--font-mono);
}

.result {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-caption);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result .el-icon { flex: none; }

.result.ok {
  color: var(--color-success);
}

.result.fail {
  color: var(--color-danger);
  cursor: default;
}

.fine-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: color-mix(in srgb, var(--color-warning) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning) 25%, transparent);
  border-radius: var(--radius-md);
  font-size: var(--fs-caption);
  color: var(--color-warning);
  line-height: var(--lh-snug);
}

.fine-note .el-icon { flex: none; }

.footer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  width: 100%;
}

.footer-row > .el-button + .el-button {
  margin-left: 0;
}

.result-summary {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.result-summary b {
  font-family: var(--font-mono);
  color: var(--color-success);
}

.result-summary b.bad {
  color: var(--color-danger);
}
</style>
