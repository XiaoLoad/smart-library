<template>
  <div class="page trace-page">
    <header class="page-header">
      <span class="eyebrow">Agent 运行日志 · AGENT TRACE</span>
      <h1>Agent 运行日志</h1>
      <p class="desc">
        记录 AI 助手每次对话的工具调用序列、决策轮次、人工授权触发情况与 token 消耗，
        可用于排障与运行质量分析。
      </p>
    </header>

    <!-- 汇总指标：把「AI 到底做了什么」变成可量化的数字 -->
    <div class="trace-summary">
      <div class="sum-card">
        <div class="sum-value">{{ summary.totalRuns ?? 0 }}</div>
        <div class="sum-label">对话轮次</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ summary.totalTools ?? 0 }}</div>
        <div class="sum-label">工具调用</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ summary.totalRounds ?? 0 }}</div>
        <div class="sum-label">循环轮次</div>
      </div>
      <div class="sum-card accent">
        <div class="sum-value">{{ summary.gateCount ?? 0 }}</div>
        <div class="sum-label">人工授权</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ summary.degradedCount ?? 0 }}</div>
        <div class="sum-label">降级收尾</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ formatDuration(summary.avgDurationMs) }}</div>
        <div class="sum-label">平均耗时</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ formatTokens(summary.promptTokens) }}</div>
        <div class="sum-label">输入 token</div>
      </div>
      <div class="sum-card">
        <div class="sum-value">{{ formatTokens(summary.completionTokens) }}</div>
        <div class="sum-label">输出 token</div>
      </div>
    </div>

    <div class="data-table">
      <div class="table-toolbar">
        <div class="left">
          <el-input
            v-model="keyword"
            placeholder="搜索提问 / 工具名 / 读者"
            clearable
            class="search-input"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select v-model="statusFilter" class="status-select" @change="onSearch">
            <el-option label="全部状态" value="" />
            <el-option label="正常完成" value="success" />
            <el-option label="降级收尾" value="degraded" />
            <el-option label="异常" value="error" />
          </el-select>
          <el-button @click="onSearch">搜索</el-button>
        </div>
        <div class="right">
          <el-button @click="loadAll">
            <span class="ico-btn"><AppIcon name="refresh" :size="15" />刷新</span>
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="traces" style="width: 100%" max-height="calc(100vh - 400px)">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="cell-sub mono">{{ formatTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="读者" width="110">
          <template #default="{ row }">
            <span class="cell-sub">{{ row.userRealName || `#${row.userId}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提问" min-width="140">
          <template #default="{ row }">
            <EllipsisText :content="row.userQuery || '—'" />
          </template>
        </el-table-column>
        <el-table-column label="工具调用" min-width="130">
          <template #default="{ row }">
            <div class="tool-chain">
              <span v-for="(t, ti) in parseTools(row.toolSequence)" :key="ti" class="tool-chip">
                {{ toolLabel(t) }}
              </span>
              <span v-if="!parseTools(row.toolSequence).length" class="cell-sub">—</span>
            </div>
          </template>
        </el-table-column>
        <!-- 指标聚合为一列：保证在常见笔记本宽度下不出现横向滚动 -->
        <el-table-column label="运行指标" width="240">
          <template #default="{ row }">
            <div class="metric-cell">
              <span class="metric-item"><i>轮</i>{{ row.toolRounds ?? 0 }}</span>
              <span class="metric-item"><i>次</i>{{ row.toolCount ?? 0 }}</span>
              <span class="metric-item"><i>token</i>{{ (row.promptTokens ?? 0) + (row.completionTokens ?? 0) }}</span>
              <span class="metric-item"><i>耗时</i>{{ formatDuration(row.durationMs) }}</span>
              <el-tooltip v-if="row.gateTriggered" :content="`触发写操作门禁：${row.gateTool || ''}`">
                <el-tag size="small" type="warning" effect="light">已拦截</el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="statusType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="64" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <!-- 详情抽屉：展示完整工具序列与决策旁白 -->
    <el-drawer v-model="detailVisible" title="运行轨迹详情" size="520px">
      <div v-if="detail" class="detail">
        <div class="detail-row">
          <span class="detail-k">提问</span>
          <span class="detail-v">{{ detail.userQuery || '—' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-k">工具序列</span>
          <span class="detail-v">
            <span v-for="(t, ti) in parseTools(detail.toolSequence)" :key="ti" class="tool-chip">
              {{ toolLabel(t) }}
            </span>
            <span v-if="!parseTools(detail.toolSequence).length">—</span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-k">决策旁白</span>
          <span class="detail-v">
            <ol v-if="parseThoughts(detail.thoughtSummary).length" class="thought-list">
              <li v-for="(t, ti) in parseThoughts(detail.thoughtSummary)" :key="ti">{{ t }}</li>
            </ol>
            <span v-else>—</span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-k">安全事件</span>
          <span class="detail-v">
            <el-tag v-if="detail.gateTriggered" size="small" type="warning" effect="light">
              触发写操作门禁 · {{ detail.gateTool }}
            </el-tag>
            <el-tag v-if="detail.degraded" size="small" type="info" effect="light" style="margin-left: 6px">
              循环超限降级
            </el-tag>
            <span v-if="!detail.gateTriggered && !detail.degraded" class="cell-sub">无</span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-k">运行指标</span>
          <span class="detail-v">
            轮次 {{ detail.toolRounds ?? 0 }} · 工具 {{ detail.toolCount ?? 0 }} ·
            输入 {{ detail.promptTokens ?? 0 }} token · 输出 {{ detail.completionTokens ?? 0 }} token ·
            耗时 {{ formatDuration(detail.durationMs) }}
          </span>
        </div>
        <div v-if="detail.errorMsg" class="detail-row">
          <span class="detail-k">异常</span>
          <span class="detail-v err">{{ detail.errorMsg }}</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { traceApi } from '@/api'
import AppIcon from '@/components/AppIcon.vue'
import EllipsisText from '@/components/EllipsisText.vue'

defineOptions({ name: 'AiAgentTrace' })

const traces = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)
const summary = reactive({})

const detailVisible = ref(false)
const detail = ref(null)

/** 拉取列表 */
const load = async () => {
  loading.value = true
  try {
    const res = await traceApi.list({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
    })
    traces.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error('加载运行日志失败')
  } finally {
    loading.value = false
  }
}

/** 拉取汇总 */
const loadSummary = async () => {
  try {
    const res = await traceApi.summary()
    Object.assign(summary, res || {})
  } catch {
    // 汇总失败不影响列表
  }
}

const loadAll = () => {
  load()
  loadSummary()
}

const onSearch = () => {
  page.value = 1
  load()
}

const openDetail = (row) => {
  detail.value = row
  detailVisible.value = true
}

/** 解析 JSON 数组字符串 */
const parseList = (raw) => {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}
const parseTools = (raw) => parseList(raw)
const parseThoughts = (raw) => parseList(raw)

/** 工具名 → 中文标签 */
const TOOL_LABELS = {
  queryBooks: '检索图书',
  queryHotBooks: '热门排行',
  queryCategories: '图书分类',
  queryAnnouncements: '公告查询',
  queryMyBorrows: '我的借阅',
  queryMyFines: '我的罚款',
  queryBorrowQuota: '借阅额度',
  queryMyNotifications: '我的通知',
  applyBorrow: '借阅申请',
  renewMyBorrow: '续借申请',
  requestReturn: '归还申请',
  adminQueryBorrows: '全馆借阅',
  adminQueryFines: '全馆罚款',
  adminQueryUsers: '读者名录',
  adminQueryStats: '运营统计',
  adminCounterProcess: '柜台办理',
}
const toolLabel = (name) => TOOL_LABELS[name] || name

const statusLabel = (s) =>
  ({ success: '正常完成', degraded: '降级收尾', error: '异常' })[s] || s || '—'

const statusType = (s) =>
  ({ success: 'success', degraded: 'info', error: 'danger' })[s] || 'info'

/** 耗时格式化 */
const formatDuration = (ms) => {
  if (!ms && ms !== 0) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m${Math.round((ms % 60000) / 1000)}s`
}

/** token 数格式化（超过 1 万用 k 表述） */
const formatTokens = (n) => {
  if (!n && n !== 0) return '—'
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '—')

onMounted(loadAll)
</script>

<style scoped>
.trace-page { display: flex; flex-direction: column; gap: var(--space-4); }

/* —— 汇总指标 —— */
.trace-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
  gap: var(--space-2);
}
.sum-card {
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg-elevated);
}
.sum-card.accent { border-color: var(--color-warning); background: rgba(196, 144, 74, 0.08); }
.sum-value {
  font-family: var(--font-mono);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-primary);
  line-height: 1.2;
}
.sum-card.accent .sum-value { color: var(--color-warning); }
.sum-label {
  margin-top: 2px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* —— 工具链 —— */
.tool-chain { display: flex; flex-wrap: wrap; gap: 4px; }

/* 运行指标：横向排列的紧凑指标组 */
.metric-cell { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; }
.metric-item {
  display: inline-flex;
  align-items: baseline;
  gap: 3px;
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  font-family: var(--font-mono);
}
.metric-item i {
  font-style: normal;
  font-family: var(--font-sans);
  color: var(--color-text-tertiary);
  font-size: var(--fs-caption);
}

.tool-chip {
  display: inline-block;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--color-bg-subtle);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

/* —— 详情 —— */
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.detail-row { display: flex; flex-direction: column; gap: var(--space-1); }
.detail-k {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
}
.detail-v {
  font-size: var(--fs-small);
  color: var(--color-text);
  line-height: var(--lh-relaxed);
}
.detail-v.err { color: var(--color-danger); }
.thought-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.thought-list li { font-style: italic; color: var(--color-text-secondary); }

.pager { display: flex; justify-content: flex-end; padding-top: var(--space-3); }
</style>
