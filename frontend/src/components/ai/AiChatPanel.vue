<template>
  <div class="panel-root">
    <!-- 消息滚动区 -->
    <div class="panel-scroll" ref="msgListRef" @scroll="onScroll">
      <div class="panel-inner">
        <!-- 欢迎屏 -->
        <div v-if="!messages.length" class="panel-welcome">
          <div class="welcome-mark">智</div>
          <p class="welcome-title">您好，请问需要什么帮助？</p>
          <div class="quick-prompts">
            <button v-for="p in quickPrompts" :key="p" class="quick-prompt" @click="input = p; send()">
              {{ p }}
            </button>
          </div>
        </div>

        <template v-for="m in messages" :key="m.id">
          <div v-if="m.role === 'user'" class="msg-row user">
            <div class="user-bubble">{{ m.content }}</div>
          </div>
          <div v-else class="msg-row assistant">
            <div class="msg-body">
              <!-- 深度思考面板 -->
              <div v-if="m.reasoningSegments && m.reasoningSegments.length" class="reason-panel">
                <button class="reason-head" @click="m.reasoningOpen = !m.reasoningOpen">
                  <span class="reason-mark">
                    <span v-if="m.reasoningStreaming" class="reason-dot"></span>
                    <AppIcon v-else name="sparkles" :size="12" />
                  </span>
                  <span class="reason-title">{{ m.reasoningStreaming ? '深度思考中' : '已深度思考' }}</span>
                  <span v-if="m.reasoningSeconds != null" class="reason-time">{{ m.reasoningSeconds }}s</span>
                </button>
                <div v-if="m.reasoningOpen" class="reason-body">
                  <div v-for="(seg, si) in m.reasoningSegments" :key="si" class="reason-seg">{{ seg }}</div>
                </div>
              </div>

              <!-- M4：Agent 决策时间线（浮动面板精简版：无序号轴，仅旁白 + 工具节点） -->
              <div v-if="m.timeline && m.timeline.length" class="timeline">
                <div v-for="(node, ni) in m.timeline" :key="ni" class="timeline-node" :class="node.kind">
                  <div v-if="node.kind === 'thought'" class="timeline-thought">{{ node.text }}</div>
                  <div v-else-if="node.kind === 'tool'" class="tool-card" :class="node.card.phase">
                    <el-icon class="tool-icon">
                      <Loading v-if="node.card.phase === 'start'" />
                      <Check v-else-if="node.card.phase === 'end'" />
                      <Close v-else-if="node.card.phase === 'error'" />
                    </el-icon>
                    <span class="tool-name">{{ toolLabel(node.card.name) }}</span>
                  </div>
                  <div v-else-if="node.kind === 'confirm'" class="timeline-confirm">
                    <el-icon><Lock /></el-icon>
                    <span>等待你的授权确认</span>
                  </div>
                </div>
              </div>

              <!-- 生成式 UI 一级内联卡片：带可视化 type 的工具快照默认平铺展开（与 /ai/chat 页一致） -->
              <div
                v-for="(card, gi) in (m.toolEvents || []).filter((e) => e.phase === 'end' && e._inlineGen)"
                :key="'gi-' + gi"
                class="gen-ui-inline"
                :data-gen-turn="card._turnMarker"
              >
                <div class="gen-ui-inline-label">
                  <el-icon><DataBoard /></el-icon>
                  <span>{{ genCardTitle(card) }}</span>
                  <span v-if="card._fromHistory" class="gen-ui-time">
                    {{ formatSnapTime(card._snapshotTime) }} 查询
                  </span>
                </div>
                <AiGenUi :snapshot="parseSnapshot(card.data)" />
              </div>

              <!-- 写操作授权卡：含参数明细与有效期倒计时（与 /ai/chat 页一致，样式紧凑版）。
                   M10：confirmCards 数组——同轮多个写操作各一张卡，互不覆盖 -->
              <div
                v-for="(card, ci) in m.confirmCards || []"
                :key="'cc-' + ci"
                class="confirm-card"
                :class="card.state"
              >
                <div class="confirm-eyebrow">
                  <el-icon><Lock /></el-icon>
                  <span>{{ confirmEyebrow(card.state) }}</span>
                </div>
                <div class="confirm-title">{{ card.title }}</div>
                <div v-if="card.rows && card.rows.length" class="confirm-rows">
                  <div v-for="(r, ri) in card.rows" :key="ri" class="confirm-row">
                    <span class="confirm-k">{{ r.key }}</span>
                    <span class="confirm-v">{{ r.value }}</span>
                  </div>
                </div>
                <div v-if="card.state === 'pending'" class="confirm-actions">
                  <label class="confirm-allow" title="勾选后本会话内同类写操作不再弹卡，直接执行">
                    <input
                      v-model="card.alwaysAllow"
                      type="checkbox"
                      class="confirm-allow-box"
                    />
                    <span>始终允许此类操作</span>
                  </label>
                  <button class="confirm-btn primary" @click="onConfirmWrite(card)">确认办理</button>
                  <button class="confirm-btn" @click="onCancelWrite(card)">取消</button>
                  <span class="confirm-ttl">{{ card.remain ?? card.ttlSeconds }} 秒内有效</span>
                </div>
                <div v-else-if="card.state === 'submitting'" class="confirm-state">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  <span>正在办理…</span>
                </div>
                <div v-else-if="card.state === 'confirmed'" class="confirm-state ok confirm-batch-result">
                  <el-icon><Check /></el-icon>
                  <span>{{ card.result?.message || '已确认并执行' }}</span>
                  <!-- M10：逐项结果内联，替代回执文字 -->
                  <div v-if="card.result?.results?.length" class="batch-result-list">
                    <div
                      v-for="(r, ri) in card.result.results"
                      :key="ri"
                      class="batch-result-item"
                      :class="{ fail: r.success === false }"
                    >
                      <span class="batch-result-title">{{ r.borrowNo || r.title || r.tool }}</span>
                      <span class="batch-result-msg">{{ r.message || (r.success ? '成功' : '失败') }}</span>
                    </div>
                  </div>
                </div>
                <div v-else-if="card.state === 'cancelled'" class="confirm-state muted">
                  <el-icon><Close /></el-icon>
                  <span>已取消本次操作</span>
                </div>
                <div v-else class="confirm-state err">
                  <el-icon><Warning /></el-icon>
                  <span>{{ card.error || '授权已失效，请重新发起' }}</span>
                </div>
              </div>

              <!-- M7 批量授权卡（batch 档位）：本轮多个写操作合并一张卡，一次确认整批 -->
              <div v-if="m.batchConfirmCard" class="confirm-card batch" :class="m.batchConfirmCard.state">
                <div class="confirm-eyebrow">
                  <el-icon><Lock /></el-icon>
                  <span>{{ batchEyebrow(m.batchConfirmCard) }}</span>
                </div>
                <div class="confirm-title">{{ m.batchConfirmCard.title }}</div>
                <div v-if="m.batchConfirmCard.rows && m.batchConfirmCard.rows.length" class="confirm-rows">
                  <div v-for="(r, ri) in m.batchConfirmCard.rows" :key="ri" class="confirm-row">
                    <span class="confirm-k">{{ r.key }}</span>
                    <span class="confirm-v">{{ r.value }}</span>
                  </div>
                </div>
                <div v-if="m.batchConfirmCard.state === 'pending'" class="confirm-actions">
                  <button class="confirm-btn primary" @click="onConfirmBatch(m.batchConfirmCard)">
                    全部确认（{{ m.batchConfirmCard.count }} 项）
                  </button>
                  <button class="confirm-btn" @click="onCancelBatch(m.batchConfirmCard)">取消</button>
                  <span class="confirm-ttl">{{ m.batchConfirmCard.remain ?? m.batchConfirmCard.ttlSeconds }} 秒内有效</span>
                </div>
                <div v-else-if="m.batchConfirmCard.state === 'submitting'" class="confirm-state">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  <span>正在批量办理…</span>
                </div>
                <div v-else-if="m.batchConfirmCard.state === 'confirmed'" class="confirm-state ok">
                  <el-icon><Check /></el-icon>
                  <span>{{ m.batchConfirmCard.result?.message || '批量办理完成' }}</span>
                </div>
                <div v-else-if="m.batchConfirmCard.state === 'cancelled'" class="confirm-state muted">
                  <el-icon><Close /></el-icon>
                  <span>已取消本批操作</span>
                </div>
                <div v-else class="confirm-state err">
                  <el-icon><Warning /></el-icon>
                  <span>{{ m.batchConfirmCard.error || '批量授权已失效，请重新发起' }}</span>
                </div>
              </div>

              <!-- M9 历史授权卡（只读）：决策态落库，刷新后仍可见 -->
              <div
                v-for="(hc, hi) in m.historyCards || []"
                :key="'hc-' + hi"
                class="confirm-card history"
                :class="hc.state"
              >
                <div class="confirm-eyebrow">
                  <el-icon><Lock /></el-icon>
                  <span>{{ historyCardEyebrow(hc) }}</span>
                  <span class="confirm-ttl">{{ (hc.createTime || '').slice(5, 10) }}</span>
                </div>
                <div class="confirm-title">{{ hc.title }}</div>
                <div v-if="hc.rows && hc.rows.length" class="confirm-rows">
                  <div v-for="(r, ri) in hc.rows" :key="ri" class="confirm-row">
                    <span class="confirm-k">{{ r.key }}</span>
                    <span class="confirm-v">{{ r.value }}</span>
                  </div>
                </div>
              </div>

              <!-- 参考资料：本轮注入的知识库来源（紧凑药丸条，点击展开片段摘要） -->
              <div v-if="m.ragSources && m.ragSources.length" class="rag-block">
                <div class="rag-row">
                  <span class="rag-label">参考资料</span>
                  <button
                    v-for="(s, si) in m.ragSources"
                    :key="si"
                    class="rag-chip"
                    :class="{ active: m.ragOpenIndex === si }"
                    :title="m.ragOpenIndex === si ? '收起片段' : '查看片段摘要'"
                    @click="m.ragOpenIndex = m.ragOpenIndex === si ? -1 : si"
                  >
                    <span class="rag-title">{{ s.docTitle }}</span>
                  </button>
                </div>
                <div v-if="m.ragOpenIndex >= 0 && m.ragSources[m.ragOpenIndex]" class="rag-snippet">
                  <span class="rag-snippet-title">{{ m.ragSources[m.ragOpenIndex].docTitle }} · 检索片段摘要</span>
                  <p>{{ m.ragSources[m.ragOpenIndex].snippet }}</p>
                </div>
              </div>

              <!-- 等待指示：有参考资料后文案随之切换（与 AI 助手页一致） -->
              <div
                v-if="m.pending && !m.reasoningSegments.length && !m.toolEvents.length && !(m.ragSources && m.ragSources.length)"
                class="thinking"
              >
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                <span class="thinking-text">正在思考…</span>
              </div>
              <div
                v-else-if="m.pending && (m.ragSources && m.ragSources.length) && !m.reasoningSegments.length && !m.toolEvents.length && !m.content"
                class="thinking"
              >
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                <span class="thinking-text">正在结合参考资料思考…</span>
              </div>

              <div class="msg-content markdown" v-html="m.html"></div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="panel-input">
      <el-input
        ref="inputRef"
        v-model="input"
        type="textarea"
        :rows="2"
        :placeholder="hasPendingConfirm ? '有待确认的授权请求，请先确认或取消' : '输入您的问题，Enter 发送'"
        resize="none"
        @keydown.enter.exact.prevent="send"
      />
      <div v-if="hasPendingConfirm" class="panel-blocked-tip">
        <el-icon><Lock /></el-icon>
        <span>授权请求等待处理中：全部确认或取消后才能继续对话</span>
      </div>
      <div class="input-actions">
        <el-button v-if="generating" size="small" @click="stopGenerate">停止</el-button>
        <el-button v-else type="primary" size="small" @click="send">发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 悬浮球内嵌 AI 会话面板：与 /ai/chat 页面共享同一套后端接口与会话列表，
 * 精简版 UI（无会话侧栏、无工具数据展开、无锚点导航）；知识库来源以紧凑
 * 药丸条呈现（点击展开片段摘要），写操作授权卡含参数明细与有效期倒计时。
 * 挂载后懒初始化（init），流式期间组件被 v-show 隐藏不销毁——后台继续输出。
 */
import { ref, reactive, computed, nextTick, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { Loading, Check, Close, Lock, Warning, DataBoard } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { aiApi } from '@/api'
import { useAiSearchStore } from '@/stores/aiSearch'
import AppIcon from '@/components/AppIcon.vue'
import AiGenUi from '@/components/ai/AiGenUi.vue'

const emit = defineEmits(['state-change'])

// 会话活动广播：悬浮球每次新建会话/发送消息都通知 AI 助手页。
// 助手页被 keep-alive 缓存、切回时不走挂载，靠 onActivated 读取该广播同步对话
const notifySessionActivity = () => {
  if (!currentConvId) return
  window.dispatchEvent(new CustomEvent('ai-session-activity', { detail: { conversationId: currentConvId } }))
}

const route = useRoute()

// AI 检索联动：queryBooks 等工具返回的图书列表写入该 store，图书检索页 watch 联动。
// 广播有页面条件收窄（见 done 回调）：仅当前在馆藏图书页时写入
const aiSearchStore = useAiSearchStore()

const md = new MarkdownIt({ html: false, breaks: true, linkify: true })

const TOOL_LABELS = {
  queryCategories: '查询图书分类',
  queryBooks: '检索馆藏图书',
  queryHotBooks: '热门图书排行',
  queryAnnouncements: '查询公告',
  queryMyBorrows: '查询借阅记录',
  queryMyFines: '查询罚款记录',
  queryBorrowQuota: '查询借阅额度',
  queryMyNotifications: '查询通知消息',
  applyBorrow: '提交借阅申请',
  renewMyBorrow: '续借图书',
  requestReturn: '申请归还图书',
  adminQueryBorrows: '查询全馆借阅',
  adminQueryFines: '查询全馆罚款',
  adminQueryUsers: '查询读者信息',
  adminQueryStats: '馆藏统计概览',
  adminCounterProcess: '柜台办理业务',
}
const toolLabel = (name) => TOOL_LABELS[name] || name

/* —— 写操作授权卡 —— */

// 授权卡眉标文案（与 /ai/chat 页保持一致）
const CONFIRM_EYEBROWS = {
  pending: '需要你的授权',
  submitting: '正在办理',
  confirmed: '已确认',
  cancelled: '已取消',
  expired: '授权失效',
  failed: '办理失败',
}
const confirmEyebrow = (state) => CONFIRM_EYEBROWS[state] || '需要你的授权'

// 确认执行：回传令牌与会话 ID，参数由服务端保存，客户端无法篡改
const onConfirmWrite = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  // 记录卡片所属消息（M11 聚合续跑判断用）
  const ownerMsg = messages.value.find((m) => (m.confirmCards || []).includes(card))
  try {
    const res = await aiApi.confirmWrite(card.token, card.tool, card.conversationId, card.alwaysAllow === true)
    card.state = 'confirmed'
    card.result = res
    reportCardDecision(card, 'confirmed')
    // M10：结果内联在授权卡上（card.result），不再追加回执文字气泡
    // M11：全部卡处理完才发起一次续跑
    maybeContinueAfterAllResolved(ownerMsg, card.tool)
  } catch (e) {
    // 失败分类：令牌类失效（过期/已被处理/不匹配）→ expired；业务错误 → failed。
    // 令牌一次性，失败后原卡无法重试，两种状态都只说明结果，不一律谎称过期
    const message = e?.message || '授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
    reportCardDecision(card, tokenInvalid ? 'expired' : 'failed')
    // M11：失效/失败也算已处理，同样走聚合判定
    maybeContinueAfterAllResolved(ownerMsg, card.tool)
  }
}

// 用户取消：卡片原地转为静默状态
const onCancelWrite = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
  reportCardDecision(card, 'cancelled')
  // M11：取消也是「已处理」，全部卡处理完触发聚合续跑
  const ownerMsg = messages.value.find((m) => (m.confirmCards || []).includes(card))
  maybeContinueAfterAllResolved(ownerMsg, card.tool || '')
}

/* —— M7 批量授权卡 —— */

const batchEyebrow = (card) => {
  const state = card.state
  if (state === 'pending') return `批量授权 · ${card.count} 项操作待确认`
  if (state === 'submitting') return '正在批量办理'
  if (state === 'confirmed') return '批量办理完成'
  if (state === 'cancelled') return '已取消'
  return '批量授权失效'
}

// 全部确认：只回传批次令牌，服务端按保存的批次顺序逐项执行
const onConfirmBatch = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  try {
    const res = await aiApi.confirmBatchWrite(card.token, card.conversationId)
    card.state = 'confirmed'
    card.result = res
    reportCardDecision(card, 'confirmed', true)
    // M10：批量结果内联在卡片上，不再追加回执文字气泡
    // M8 确认续跑：批量办理完成后自动发起下一轮
    continueAfterConfirm(`批量办理 ${res?.successCount ?? 0} 项成功`)
  } catch (e) {
    const message = e?.message || '批量授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
    reportCardDecision(card, tokenInvalid ? 'expired' : 'failed', true)
  }
}

// 取消本批：批次到期自动失效，本地置灰即可
const onCancelBatch = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
  reportCardDecision(card, 'cancelled', true)
  // 批量卡取消也必须回执给模型，否则模型误认为操作悬而未决而重发授权卡
  continueAfterConfirm(card.title || '本批写操作', { rejected: true })
}

// M9 上报授权卡决策态（展示层记录，失败不影响主流程）
const reportCardDecision = (card, decision, batch = false) => {
  if (!card || !currentConvId) return
  aiApi.recordCardDecision({
    conversationId: currentConvId,
    decision,
    token: card.token || '',
    tool: card.tool || '',
    title: card.title || '',
    rows: Array.isArray(card.rows) ? card.rows : [],
    batch,
  }).catch(() => { /* 展示层记录失败不影响主流程 */ })
}

/**
 * M10：把确认执行的写操作结果应用到对应授权卡（对齐 ZCode 的行内状态机）。
 * 找不到 submitting/pending 卡片时静默忽略——确认接口返回值已在本地更新过卡片。
 */
const applyToolResult = (info) => {
  if (!info?.tool) return
  const candidates = []
  for (const m of messages.value) {
    for (const c of m.confirmCards || []) {
      if (c.tool === info.tool) candidates.push(c)
    }
    if (m.batchConfirmCard && (info.tool === 'adminCounterProcessBatch' || m.batchConfirmCard.tool === info.tool)) {
      candidates.push(m.batchConfirmCard)
    }
  }
  let card = info.token
    ? candidates.find((c) => c.token === info.token)
    : candidates.reverse().find((c) => c.state === 'submitting' || c.state === 'pending')
  if (!card && info.token) {
    // token 未命中（页面刷新后恢复的卡）退化为按状态匹配
    card = candidates.reverse().find((c) => c.state === 'submitting' || c.state === 'pending')
  }
  if (!card) return
  stopConfirmCountdown(card)
  card.state = info.success === false ? 'failed' : 'confirmed'
  card.result = info
  if (info.success === false) card.error = info.message || '执行失败'
}

// M9 历史授权卡（只读）的眉标
const historyCardEyebrow = (hc) => {
  const prefix = hc.batch ? '批量授权' : '授权'
  const stateText = {
    confirmed: '已确认',
    cancelled: '已取消',
    expired: '已过期',
    failed: '办理失败',
  }[hc.state] || hc.state
  return `${prefix} · ${stateText}`
}

// 授权过期：卡片灰态保留，说明发生过什么而非凭空消失
const onConfirmExpired = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'expired'
  card.error = '授权已过期，请重新发起该操作'
  reportCardDecision(card, 'expired', !!card.batchFlag)
}

// 授权卡倒计时：本地每秒递减 remain，归零触发 onConfirmExpired（服务端 TTL 的前端镜像）
const startConfirmCountdown = (card) => {
  stopConfirmCountdown(card)
  card.remain = card.ttlSeconds
  card._timer = setInterval(() => {
    card.remain -= 1
    if (card.remain <= 0) onConfirmExpired(card)
  }, 1000)
}
const stopConfirmCountdown = (card) => {
  if (card?._timer) {
    clearInterval(card._timer)
    card._timer = null
  }
}

const quickPrompts = ['我当前借了哪些书？', '查找哲学类图书', '本月借阅最多的书是什么？', '我有未缴罚款吗？']

const messages = ref([])
const input = ref('')
const inputRef = ref(null)
const msgListRef = ref(null)
const generating = ref(false)
let currentConvId = null
let abortController = null
let assistantMsg = null
let inited = false

// 登录态失效统一处理（与 /ai/chat 页 handleUnauthorized 一致，SSE fetch 路径专用）：
// 清 token → 提示 → 跳登录页（携带 redirect 便于登录后回跳），防重复跳转
let redirectingToLogin = false
const handleUnauthorized = (message) => {
  localStorage.removeItem('library_token')
  if (redirectingToLogin || window.location.pathname === '/login') return
  redirectingToLogin = true
  ElMessage.error(message || '登录已过期，请重新登录')
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  setTimeout(() => {
    window.location.href = `/login?redirect=${redirect}`
  }, 500)
}

const renderMarkdown = (content) => {
  if (!content) return ''
  try { return md.render(content) } catch { return md.utils.escapeHtml(content) }
}

/** 工具快照 JSON → 对象（供 AiGenUi 渲染），解析失败返回 null */
const parseSnapshot = (dataJson) => {
  if (!dataJson || typeof dataJson !== 'string') return null
  try {
    return JSON.parse(dataJson.replace(/…$/, ''))
  } catch {
    return null
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgListRef.value) msgListRef.value.scrollTop = msgListRef.value.scrollHeight
}

// ===== 生成式 UI 一级内联卡片（与 /ai/chat 页逻辑一致） =====
const GEN_CARD_TITLES = {
  book_grid: '图书卡片',
  rank_list: '排行榜',
  quota_bar: '借阅额度',
  category_grid: '分类分布',
  announce_list: '公告列表',
  borrow_table: '借阅记录',
  fine_table: '罚款记录',
  user_table: '用户列表',
  stats_dashboard: '统计看板',
}
const GEN_UI_TYPES = new Set(Object.keys(GEN_CARD_TITLES))
const genCardTitle = (card) => GEN_CARD_TITLES[card._genType] || '数据卡片'

/** 快照时点展示（历史卡片角标）：今天显示时分，更早显示 月-日 时:分 */
const formatSnapTime = (t) => {
  if (!t) return '历史'
  const d = new Date(t)
  if (isNaN(d.getTime())) return '历史'
  const pad = (n) => String(n).padStart(2, '0')
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}-${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 解析落库的工具快照数组为 toolEvents 形态（历史消息重建内联卡片）。
 * 历史卡片标记 _fromHistory（时点角标判定）。
 */
const parseStoredSnapshots = (snapshotsJson) => {
  if (!snapshotsJson || typeof snapshotsJson !== 'string') return []
  try {
    const arr = JSON.parse(snapshotsJson)
    if (!Array.isArray(arr)) return []
    // M9：card_decision 记录走 historyCards 通道，不混入查询快照
    return arr
      .filter((rec) => rec && rec.kind !== 'card_decision')
      .map((rec) => ({
        name: rec.tool || '',
        phase: 'end',
        data: rec.data ? JSON.stringify(rec.data) : null,
        _inlineGen: true,
        _genType: rec.type || '',
        _fromHistory: true,
        _snapshotTime: rec.createTime || '',
      }))
  } catch {
    return []
  }
}

/** M9：从快照通道解析授权卡决策记录为只读历史卡片（与助手页一致） */
const parseStoredCardDecisions = (snapshotsJson) => {
  if (!snapshotsJson || typeof snapshotsJson !== 'string') return []
  try {
    const arr = JSON.parse(snapshotsJson)
    if (!Array.isArray(arr)) return []
    return arr
      .filter((rec) => rec && rec.kind === 'card_decision')
      .map((rec) => ({
        tool: rec.tool || '',
        title: rec.title || '',
        rows: Array.isArray(rec.rows) ? rec.rows : [],
        state: rec.state || 'expired',
        batch: rec.batch === true,
        createTime: rec.createTime || '',
      }))
  } catch {
    return []
  }
}

/** 判定工具快照是否带可视化 type，是则打 _inlineGen 标志提升为一级内联卡片 */
const markInlineGen = (card) => {
  try {
    const snap = JSON.parse((card.data || '').replace(/…$/, ''))
    if (snap && GEN_UI_TYPES.has(snap.type)) {
      card._inlineGen = true
      card._genType = snap.type
    }
  } catch { /* 截断/异常快照回落原折叠行为 */ }
}

/** done 后滚动定位：本轮有内联卡片时滚到本轮第一张卡片。
 *  通过 data-gen-turn 标记精确定位——面板消息列表同样可能含历史卡片，
 *  全局 querySelector 会命中更早的卡片导致滚错位置 */
const scrollToFirstGenCard = async (msg) => {
  await nextTick()
  if (!msg) return
  const marker = `gen-turn-${msg.id}`
  msg.toolEvents?.forEach((e) => { if (e._inlineGen) e._turnMarker = marker })
  await nextTick()
  const el = msgListRef.value
  if (!el) return
  const first = el.querySelector(`[data-gen-turn="${marker}"]`)
  if (first) el.scrollTo({ top: first.offsetTop - el.offsetTop - 8, behavior: 'smooth' })
}

const onScroll = () => {
  const el = msgListRef.value
  if (!el) return
  const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (atBottom) showJump()
}
// 悬浮球面板精简版：不做智能跟随，总是滚底
const showJump = () => {}

/** 挂载后首次展开时调用：加载最近会话 */
const init = async () => {
  if (inited) return
  inited = true
  try {
    const data = await aiApi.conversationList()
    const list = data.list || []
    if (list.length) {
      currentConvId = list[0].id
      const md2 = await aiApi.messageList(currentConvId)
      messages.value = (md2.list || []).map((m) => ({
        ...m,
        html: renderMarkdown(m.content),
        // 工具快照落库后历史消息可重建内联生成式卡片（时点数据，卡片带时间角标）
        toolEvents: parseStoredSnapshots(m.toolSnapshots),
        // M9：历史授权卡（决策态）重建为只读卡片
        historyCards: parseStoredCardDecisions(m.toolSnapshots),
        reasoningSegments: [],
        reasoningOpen: false,
        reasoningStreaming: false,
        reasoningSeconds: null,
        pending: false,
        // M4：thought 旁白不落库，历史消息无时间线
        timeline: [],
      })).reverse()
    }
    await scrollToBottom()
  } catch { /* 静默 */ }
}

const stopGenerate = () => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  generating.value = false
  emitState(false, false)
}

const emitState = (gen, justFinished) => {
  emit('state-change', { generating: gen, justFinished })
}

/** 最近一次发送的用户原话（queryBooks 结果联动图书页时展示用） */
const lastUserText = ref('')
// 检索联动候补：本轮工具返回的图书快照先暂存，流结束（done）且本轮无写操作时才广播——
// 借书等写流程中模型也会先调 queryBooks 拿书名，那是流程中间步骤，不应联动图书页筛选
let pendingAiSearch = null
// 本轮是否出现写操作授权（confirm 事件，仅写操作会触发门禁）——出现则本轮不广播检索联动
let turnHasWrite = false
// 写意图关键词：用户原话带借/还/续借等写意图时同样不广播——模型在借书前常先查库存、
// 确认版本（不调写工具、无 confirm 事件），如「请先选定一版，我再提交借阅申请」
const WRITE_INTENT_RE = /(借阅|帮我借|想借|要借|借下|借一下|借一本|借这本|借了|续借|归还|还书|罚款)/
let turnWriteIntent = false

/**
 * M11（对齐 ZCode 输入区阻塞语义）：存在待确认授权卡时禁止发起新对话。
 */
const hasPendingConfirm = computed(() =>
  messages.value.some((m) =>
    (m.confirmCards || []).some((c) => c.state === 'pending') ||
    m.batchConfirmCard?.state === 'pending'
  )
)

const send = async () => {
  if (generating.value) return
  if (hasPendingConfirm.value) {
    ElMessage.warning('请先处理上方待确认的授权请求（确认或取消）后再继续对话')
    return
  }
  if (!input.value.trim()) return
  const content = input.value.trim()
  input.value = ''
  await sendInternal(content, { continuation: false })
}

/**
 * M8 确认续跑：授权确认成功后自动发起的下一轮对话（面板内与助手页行为一致）。
 * M11 聚合续跑：本轮全部授权卡处理完毕才调用。
 */
const continueAfterConfirm = async (toolSummary, { rejected = false } = {}) => {
  if (generating.value || !currentConvId) return
  // rejected：本轮授权被用户取消。必须把「已拒绝」明确回执给模型——
  // 否则模型读到「尚无成功回执的剩余项」会反复重发授权卡
  const guide = rejected
    ? `本轮的授权请求（${toolSummary}）已被用户取消，所有操作均未执行。用户已明确拒绝本次办理，禁止再发起任何授权卡或写操作工具调用；请简短回应已按用户要求取消，并可询问是否需要其他帮助。`
    : `授权处理已完成（${toolSummary}）。请逐项核对历史回执：已成功的项不要重复办理；已被用户取消的项是用户明确拒绝，禁止再次发起授权；仅当剩余项是因授权过期未执行时，才可为它们重新发起授权；若全部完成，请逐项列出单号汇总。`
  await sendInternal(guide, { continuation: true })
}

/**
 * M11 聚合续跑：检查所属消息的授权卡是否已全部处理（非 pending），
 * 全部处理完才发起一次续跑，汇总语带各卡结果（成功/跳过）。
 */
const maybeContinueAfterAllResolved = (msg, toolSummary) => {
  const cards = (msg?.confirmCards || []).filter((c) => c.tool)
  if (!cards.length || cards.some((c) => c.state === 'pending')) return
  const ok = cards.filter((c) => c.state === 'confirmed').length
  const skipped = cards.length - ok
  // 全部被取消：走拒绝回执，防止模型误以为操作未完成而重发授权卡
  if (ok === 0) {
    continueAfterConfirm(toolSummary, { rejected: true })
    return
  }
  continueAfterConfirm(`${toolSummary}；本轮授权共 ${cards.length} 项：成功 ${ok} 项，跳过 ${skipped} 项`)
}

const sendInternal = async (content, { continuation }) => {
  if (generating.value) return
  lastUserText.value = continuation ? lastUserText.value : content
  // 新一轮：重置检索联动候补与写操作标记
  pendingAiSearch = null
  turnHasWrite = false
  turnWriteIntent = WRITE_INTENT_RE.test(content)

  if (!currentConvId) {
    try {
      const conv = await aiApi.createConversation()
      currentConvId = conv.id
      notifySessionActivity()
    } catch {
      ElMessage.error('创建会话失败')
      return
    }
  }

  // 推送用户消息（续跑轮引导语不显示用户气泡，避免伪造用户发言）
  if (!continuation) {
    messages.value.push({
      id: Date.now(),
      role: 'user',
      content,
      createTime: new Date().toISOString(),
    })
  }
  generating.value = true
  emitState(true, false)
  scrollToBottom()

  abortController = new AbortController()
  // reactive 包装：流式增量直接写入本引用，模板实时更新（打字机效果）
  assistantMsg = reactive({
    id: Date.now() + 1,
    role: 'assistant',
    content: '',
    html: '',
    toolEvents: [],
    reasoningSegments: [],
    reasoningOpen: true,
    reasoningStreaming: false,
    reasoningSeconds: null,
    _reasonStartAt: 0,
    _reasonTimer: null,
    _reasonSegOpen: false,
    pending: true,
    // 知识库来源（SSE rag 事件），ragOpenIndex 为当前展开的片段序号，-1 表示全部收起
    ragSources: [],
    ragOpenIndex: -1,
    // 写操作授权卡（SSE confirm 事件；M10 数组——同轮多个写操作各一张卡）
    confirmCards: [],
    // M7 批量授权卡（SSE batch_confirm 事件，batch 档位）
    batchConfirmCard: null,
    // M4：Agent 决策时间线
    timeline: [],
    createTime: new Date().toISOString(),
  })
  messages.value.push(assistantMsg)
  scrollToBottom()
  notifySessionActivity()

  const stopReasonTimer = (msg) => {
    if (!msg?._reasonTimer) return
    clearInterval(msg._reasonTimer)
    msg._reasonTimer = null
    if (msg._reasonStartAt) {
      msg.reasoningSeconds = ((Date.now() - msg._reasonStartAt) / 1000).toFixed(1)
    }
  }

  try {
    const token = localStorage.getItem('library_token') || ''
    const resp = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ conversationId: currentConvId, content, continuation }),
      signal: abortController.signal,
    })
    const ct = resp.headers.get('content-type') || ''
    if (!ct.includes('text/event-stream')) {
      let msg = 'AI 请求失败'
      let code = null
      try {
        const errBody = await resp.json()
        msg = errBody.message || msg
        code = errBody.code
      } catch { /* 忽略 */ }
      // 与 /ai/chat 页同约定：未授权返回 body.code=401，SSE fetch 不走 axios 拦截器，这里复刻登出跳转
      if (code === 401) {
        handleUnauthorized(msg)
      }
      throw new Error(msg)
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    const handleEvent = (block) => {
      const lines = block.split('\n')
      let event = 'message'
      const dataParts = []
      for (const line of lines) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) {
          let d = line.slice(5)
          if (d.startsWith(' ')) d = d.slice(1)
          dataParts.push(d)
        }
      }
      const data = dataParts.join('\n')
      const msg = assistantMsg
      if (event === 'thought') {
        // M4：Agent 决策旁白，作为时间线节点插入
        msg.pending = false
        if (data) {
          msg.timeline.push({ kind: 'thought', text: data })
          scrollToBottom()
        }
      } else if (event === 'thinking') {
        msg.pending = false
        if (data) {
          if (!msg._reasonSegOpen) {
            msg.reasoningSegments.push('')
            msg._reasonSegOpen = true
          }
          if (!msg.reasoningStreaming) {
            msg.reasoningStreaming = true
            msg._reasonStartAt = Date.now()
            msg._reasonTimer = setInterval(() => {
              msg.reasoningSeconds = ((Date.now() - msg._reasonStartAt) / 1000).toFixed(1)
            }, 100)
          }
          const segs = msg.reasoningSegments
          segs[segs.length - 1] += data
          scrollToBottom()
        }
      } else if (event === 'delta') {
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content += data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'reset') {
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content = ''
        msg.html = ''
      } else if (event === 'final') {
        msg.content = data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'tool') {
        msg.pending = false
        try {
          const info = JSON.parse(data)
          const events = msg.toolEvents
          if (info.phase === 'start') {
            const card = { name: info.name || '', phase: 'start' }
            events.push(card)
            // M4：工具调用记入时间线
            msg.timeline.push({ kind: 'tool', name: card.name, detail: '', card })
          } else {
            let target = null
            for (let i = events.length - 1; i >= 0; i--) {
              if (events[i].name === (info.name || '') && events[i].phase === 'start') { target = events[i]; break }
            }
            if (target) {
              target.phase = info.phase
              if (info.phase === 'end' && info.data) {
                target.data = info.data
                markInlineGen(target)
              }
            } else {
              const fbCard = { name: info.name || '', phase: info.phase }
              if (info.phase === 'end' && info.data) {
                fbCard.data = info.data
                markInlineGen(fbCard)
              }
              events.push(fbCard)
            }
          }
          // AI 检索联动：工具返回的图书快照先暂存候补，done 时统一判定。
          // 不限定工具名：只要快照含图书条目（items 数组且元素带 title）即成为候补；
          // 本轮若带写操作（如借书前的 queryBooks），候补不广播
          if (info.phase === 'end' && info.data) {
            try {
              const snap = JSON.parse(info.data)
              const items = snap.items
              if (Array.isArray(items) && items.length && items[0]?.title) {
                pendingAiSearch = { query: lastUserText.value || '', items, total: snap.total }
              }
            } catch (e) { console.warn('[AI联动] 快照解析失败', info.name, e) }
          }
        } catch { /* 忽略 */ }
        scrollToBottom()
      } else if (event === 'rag') {
        // 知识库来源：在调用 LLM 之前推送（早于 delta），按「标题 + 摘要」去重后累计。
        // 不关 pending：占位文案切换为「正在结合参考资料思考…」，覆盖到模型首个输出之间的空窗
        try {
          const list = JSON.parse(data)
          if (Array.isArray(list)) {
            for (const item of list) {
              const docTitle = item?.docTitle || '未命名文档'
              const snippet = item?.snippet || ''
              const dup = msg.ragSources.some(
                (s) => s.docTitle === docTitle && s.snippet === snippet
              )
              if (!dup) msg.ragSources.push({ docTitle, snippet })
            }
          }
        } catch { /* 解析失败不影响对话 */ }
      } else if (event === 'confirm') {
        // 写操作授权请求：模型发起写操作被门禁拦截，改为向用户请求授权。
        // 本轮出现写操作：检索候补不再广播（借书前的 queryBooks 只是流程中间步骤）
        turnHasWrite = true
        msg.pending = false
        try {
          const info = JSON.parse(data)
          // M10：同轮可能有多个写操作，卡进数组互不覆盖
          if (!msg.confirmCards) msg.confirmCards = []
          msg.confirmCards.push({
            token: info.token,
            tool: info.tool,
            title: info.title || '写操作授权',
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 60,
            // 确认请求回传，供服务端校验令牌与会话归属一致
            conversationId: currentConvId,
            // M8「本会话始终允许」勾选状态
            sessionAllow: info.sessionAllow === true,
            alwaysAllow: false,
            // pending: 等待确认 / confirmed: 已确认 / cancelled: 用户取消 / expired: 已过期 / failed: 办理失败
            state: 'pending',
            result: null,
            error: '',
          })
          // M4：授权请求记入时间线
          msg.timeline.push({ kind: 'confirm', tool: info.tool || '' })
          msg.content = msg.content || '请确认上方授权请求后继续。'
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.confirmCards[msg.confirmCards.length - 1])
          scrollToBottom()
        } catch { /* 解析失败不影响对话 */ }
      } else if (event === 'batch_confirm') {
        // M7 批量授权卡（batch 档位）：本轮多个写操作合并一张卡，一次确认整批。
        // 后端在 done 前推送；处理逻辑与 /ai/chat 页一致
        turnHasWrite = true
        msg.pending = false
        try {
          const info = JSON.parse(data)
          msg.batchConfirmCard = {
            token: info.token,
            title: info.title || '批量授权',
            count: info.count || 0,
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 120,
            conversationId: currentConvId,
            batchFlag: true,
            // pending: 等待确认 / submitting / confirmed / cancelled / expired / failed
            state: 'pending',
            result: null,
            error: '',
          }
          msg.timeline.push({ kind: 'confirm', tool: 'batch:' + (info.count || 0) })
          msg.content = msg.content || `本轮共 ${info.count || 0} 项写操作待授权，请在下方批量卡上一并确认。`
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.batchConfirmCard)
          scrollToBottom()
        } catch { /* 解析失败不影响对话 */ }
      } else if (event === 'tool_result') {
        // M10：确认执行的写操作结果，授权卡原地更新（结果内联），不再出现回执气泡
        try {
          const info = JSON.parse(data)
          applyToolResult(info)
        } catch { /* 解析失败忽略 */ }
      } else if (event === 'degrade') {
        // 工具循环超限降级：非报错样式，提示已完成部分 + 建议继续追问
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        let tip = '任务较复杂，已为你简化处理。'
        try {
          const info = JSON.parse(data)
          if (info?.message) tip = info.message
        } catch { /* 解析失败用默认文案 */ }
        msg.content = msg.content ? `${msg.content}\n\n${tip}` : tip
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'done' || event === 'error') {
        generating.value = false
        emitState(false, true)
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        // 流结束：检索联动广播（双重条件收窄，防止跨页污染馆藏图书页筛选）：
        // ① 页面条件——当前必须在馆藏图书页（/book），其他页面聊查书结果只留在对话内；
        // ② 内容条件——本轮既无写操作（confirm 事件）也无写意图（用户原话），借书前的
        //    queryBooks 只是流程中间步骤，不广播
        if (pendingAiSearch) {
          if (route.path.startsWith('/book') && !turnHasWrite && !turnWriteIntent) {
            aiSearchStore.setResult(pendingAiSearch.query, pendingAiSearch.items, pendingAiSearch.total)
          }
          pendingAiSearch = null
        }
        turnHasWrite = false
        turnWriteIntent = false
        if (event === 'error') {
          // 已有正文时保留原内容，错误说明追加其后（避免中途出错抹掉已流出的回答）
          const errTip = `⚠️ ${data || 'AI 请求出错'}`
          msg.content = msg.content ? `${msg.content}\n\n${errTip}` : errTip
          msg.html = renderMarkdown(msg.content)
        }
        // 流结束滚动定位：本轮有内联生成式卡片时滚到本轮第一张卡片，否则维持滚底
        if (event === 'done' && (msg.toolEvents || []).some((e) => e._inlineGen)) {
          scrollToFirstGenCard(msg)
        }
      }
    }
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (block.trim()) handleEvent(block)
      }
    }
    if (buffer.trim()) handleEvent(buffer)
  } catch (e) {
    generating.value = false
    emitState(false, true)
    if (e.name !== 'AbortError') {
      ElMessage.error(e.message || 'AI 请求失败')
      if (assistantMsg) {
        // 已有正文时保留原内容，错误说明追加其后
        const errTip = `⚠️ ${e.message || 'AI 请求失败'}`
        assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${errTip}` : errTip
        assistantMsg.html = renderMarkdown(assistantMsg.content)
      }
    }
  } finally {
    abortController = null
    // 兜底复位：服务端超时/断网导致流关闭但 done/error 未到达时，
    // generating 不复位会永久锁死输入框（send 守卫再也过不去）
    if (generating.value) {
      generating.value = false
      emitState(false, true)
    }
    if (assistantMsg) {
      assistantMsg.pending = false
      stopReasonTimer(assistantMsg)
      assistantMsg._reasonSegOpen = false
      assistantMsg.reasoningStreaming = false
      assistantMsg.toolEvents?.forEach((t) => {
        if (t.phase === 'start') t.phase = 'end'
      })
    }
  }
}

const destroy = () => {
  if (abortController) abortController.abort()
  abortController = null
}

onBeforeUnmount(() => {
  destroy()
  messages.value.forEach((m) => {
    for (const c of m.confirmCards || []) stopConfirmCountdown(c)
    stopConfirmCountdown(m.batchConfirmCard)
  })
})

/** 预填输入框（不自动发送），供页面唤起 AI 时带入话术 */
const prefill = (text) => {
  input.value = text || ''
  nextTick(() => inputRef.value?.focus())
}
defineExpose({ init, destroy, send, prefill })
</script>

<style scoped>
.panel-root {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.panel-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px;
}
.panel-welcome {
  text-align: center;
  padding: 36px 0 20px;
}
.welcome-mark {
  width: 44px;
  height: 44px;
  margin: 0 auto 10px;
  border-radius: 12px;
  background: var(--color-primary, #2f6f4f);
  color: var(--color-text-inverse, #fff);
  font-family: var(--font-serif, serif);
  font-size: 22px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.welcome-title {
  font-size: 14px;
  color: var(--color-text-secondary, #666);
  margin: 0 0 14px;
}
.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}
.quick-prompt {
  border: 1px solid var(--color-border, #ddd);
  background: var(--color-bg, #fafafa);
  color: var(--color-text-secondary, #555);
  border-radius: 999px;
  font-size: 12px;
  padding: 5px 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.quick-prompt:hover {
  color: var(--color-primary, #2f6f4f);
  border-color: var(--color-primary, #2f6f4f);
  background: var(--color-primary-light, #eaf5ef);
}

.msg-row { margin-bottom: 12px; }
.msg-row.user { display: flex; justify-content: flex-end; }
.user-bubble {
  max-width: 82%;
  background: var(--color-primary-light, #eaf5ef);
  color: var(--color-text, #333);
  padding: 8px 12px;
  border-radius: 12px 4px 12px 12px;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}
.msg-body { min-width: 0; }
.markdown {
  font-size: 14px;
  line-height: 1.6;
  color: var(--color-text, #333);
  word-break: break-word;
}
.markdown :deep(pre) {
  background: var(--color-bg-subtle, #f4f4f5);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
  font-size: 12px;
}
.markdown :deep(code) { font-family: var(--font-mono, monospace); }
.markdown :deep(p) { margin: 0 0 8px; }
.markdown :deep(p:last-child) { margin-bottom: 0; }
.markdown :deep(ul), .markdown :deep(ol) { margin: 0 0 8px; padding-left: 20px; }

/* 深度思考面板（精简版） */
.reason-panel { margin-bottom: 8px; }
.reason-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--color-bg-subtle, #f4f4f5);
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  cursor: pointer;
}
.reason-title { color: var(--color-primary, #2f6f4f); }
.reason-time { font-family: var(--font-mono, monospace); }
.reason-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: var(--color-primary, #2f6f4f);
  animation: reason-pulse 1.2s ease-in-out infinite;
}
@keyframes reason-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}
.reason-body {
  margin-top: 6px;
  padding: 8px 10px;
  border-left: 2px solid var(--color-divider, #e5e5e5);
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 140px;
  overflow-y: auto;
}
.reason-seg { margin-bottom: 4px; }

/* M4：Agent 决策时间线（浮动面板精简版：无序号轴） */
.timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.timeline-node { display: flex; flex-direction: column; align-items: flex-start; }
.timeline-thought {
  padding-left: 8px;
  border-left: 2px solid var(--color-divider, #ece6dd);
  font-size: 12px;
  font-style: italic;
  line-height: 1.7;
  color: var(--color-text-tertiary, #9c958d);
}
.timeline-confirm {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px dashed var(--color-warning, #c4904a);
  color: var(--color-warning, #c4904a);
  background: var(--color-bg-elevated, #fff);
  font-size: 12px;
}

/* 工具卡片（精简版） */
.tool-events { margin-bottom: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.tool-card {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--color-bg-subtle, #f4f4f5);
  color: var(--color-text-secondary, #666);
}
.tool-card.end .tool-icon { color: var(--color-success, #2f9e63); }
.tool-card.error .tool-icon { color: var(--color-danger, #c0564a); }
.tool-card.start .tool-icon { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 生成式 UI 一级内联卡片（与 /ai/chat 页一致） ===== */
.gen-ui-inline {
  margin: 8px 0;
  padding: 10px 12px 12px;
  border-radius: 6px;
  background: var(--color-bg-subtle, #f5f2ec);
  border: 1px solid var(--color-border, #e8e3dc);
}
.gen-ui-inline-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--color-text-secondary, #6b6560);
}
.gen-ui-inline-label .el-icon { color: var(--color-primary, #3a5a6b); }
.gen-ui-time {
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
  background: var(--color-bg-elevated, #fff);
  border: 1px solid var(--color-border, #e8e3dc);
}

/* 写操作授权卡（浮动面板简化版：卡片 + 靛蓝竖条，与工具 pill 形态区分） */
.confirm-card {
  margin-bottom: 8px;
  padding: 10px 12px;
  border-radius: 4px;
  border: 1px solid var(--color-border, #e8e3dc);
  border-left: 3px solid var(--color-primary, #3a5a6b);
  background: var(--color-bg-elevated, #fff);
}
.confirm-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.08em;
  color: var(--color-primary, #3a5a6b);
}
.confirm-title {
  margin-top: 4px;
  font-family: var(--font-serif, serif);
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text, #2c2825);
}
.confirm-actions { margin-top: 10px; display: flex; align-items: center; gap: 8px; }
.confirm-btn {
  padding: 5px 14px;
  border-radius: 4px;
  border: 1px solid var(--color-border, #e8e3dc);
  background: var(--color-bg-elevated, #fff);
  color: var(--color-text-secondary, #6b6560);
  font-size: 12px;
  cursor: pointer;
  transition: all 150ms cubic-bezier(0.25, 0.1, 0.25, 1);
}
.confirm-btn:hover { border-color: var(--color-border-strong, #d4cdc2); color: var(--color-text, #2c2825); }
.confirm-btn.primary {
  background: var(--color-primary, #3a5a6b);
  border-color: var(--color-primary, #3a5a6b);
  color: var(--color-text-inverse, #faf8f5);
}
.confirm-btn.primary:hover { background: var(--color-primary-hover, #2f4a59); }
.confirm-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--color-text-secondary, #6b6560);
}
.confirm-state.ok { color: var(--color-success, #5d8a6b); }
.confirm-state.muted { color: var(--color-text-tertiary, #9c958d); }
.confirm-state.err { color: var(--color-danger, #a85a4a); }
.confirm-card.confirmed,
.confirm-card.cancelled,
.confirm-card.expired { border-left-color: var(--color-border-strong, #d4cdc2); }
.confirm-card.confirmed .confirm-eyebrow { color: var(--color-success, #5d8a6b); }
.confirm-card.cancelled .confirm-eyebrow { color: var(--color-text-tertiary, #9c958d); }
.confirm-card.expired .confirm-eyebrow { color: var(--color-danger, #a85a4a); }
.confirm-card.failed .confirm-eyebrow { color: var(--color-danger, #a85a4a); }

/* 授权卡参数明细与有效期（/ai/chat 页样式的紧凑版，变量带兜底色） */
.confirm-rows {
  margin-top: 10px;
  border-top: 1px solid var(--color-divider, #eae6df);
}
.confirm-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--color-divider, #eae6df);
}
.confirm-row:last-child { border-bottom: none; }
.confirm-k {
  flex-shrink: 0;
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-tertiary, #9c958d);
}
.confirm-v {
  font-size: var(--fs-caption, 12px);
  color: var(--color-text, #2c2825);
  text-align: right;
  word-break: break-all;
}
.confirm-ttl {
  margin-left: auto;
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-tertiary, #9c958d);
}
/* M8 「始终允许」勾选项（面板紧凑版） */
.confirm-allow {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-secondary, #5c564f);
  cursor: pointer;
  user-select: none;
}
.confirm-allow-box {
  accent-color: var(--color-primary, #2f6f4f);
  cursor: pointer;
}
/* M9 历史授权卡（只读）：灰调、无交互 */
.confirm-card.history {
  opacity: 0.75;
  pointer-events: none;
}
.confirm-card.history .confirm-eyebrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* 知识库来源（紧凑药丸条，点击展开片段摘要） */
.rag-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 8px;
}
.rag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.rag-label {
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-tertiary, #9c958d);
}
.rag-chip {
  display: inline-flex;
  align-items: center;
  max-width: 200px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px dashed var(--color-border, #d4cdc2);
  background: var(--color-bg-elevated, #fff);
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-secondary, #5c564f);
  cursor: pointer;
}
.rag-chip:hover { border-color: var(--color-primary, #2f6f4f); color: var(--color-primary, #2f6f4f); }
.rag-chip.active {
  border-style: solid;
  border-color: var(--color-primary, #2f6f4f);
  color: var(--color-primary, #2f6f4f);
}
.rag-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-snippet {
  max-width: 100%;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--color-border, #d4cdc2);
  background: var(--color-bg-subtle, #f4f4f5);
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-secondary, #5c564f);
}
.rag-snippet-title { display: block; }
.rag-snippet p {
  margin: 4px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 等待指示 */
.thinking {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: var(--color-bg-subtle, #f4f4f5);
  border-radius: 12px;
  margin-bottom: 8px;
}
.dot {
  width: 5px; height: 5px;
  border-radius: 50%;
  background: var(--color-primary, #2f6f4f);
  animation: dot-bounce 1.2s ease-in-out infinite;
}
.dot:nth-child(2) { animation-delay: 0.15s; }
.dot:nth-child(3) { animation-delay: 0.3s; }
@keyframes dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-4px); opacity: 1; }
}
.thinking-text { font-size: 12px; color: var(--color-text-tertiary, #888); margin-left: 4px; }

/* 输入区 */
.panel-input {
  border-top: 1px solid var(--color-divider, #eee);
  padding: 10px 14px;
  flex-shrink: 0;
}
/* M11：授权等待中的阻塞提示 */
.panel-blocked-tip {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-warning, #a8843c);
}
.input-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

/* M10：授权卡结果内联（与 /ai/chat 页样式一致） */
.confirm-batch-result {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}
.batch-result-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.batch-result-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}
.batch-result-item .batch-result-title { color: var(--color-text-secondary, #5c564f); flex-shrink: 0; }
.batch-result-item .batch-result-msg { color: var(--color-success, #5d8a6b); text-align: right; word-break: break-all; }
.batch-result-item.fail .batch-result-msg { color: var(--color-danger, #a85a4a); }
</style>
