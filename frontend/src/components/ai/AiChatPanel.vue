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

              <!-- 写操作授权卡：含参数明细与有效期倒计时（与 /ai/chat 页一致，样式紧凑版） -->
              <div v-if="m.confirmCard" class="confirm-card" :class="m.confirmCard.state">
                <div class="confirm-eyebrow">
                  <el-icon><Lock /></el-icon>
                  <span>{{ confirmEyebrow(m.confirmCard.state) }}</span>
                </div>
                <div class="confirm-title">{{ m.confirmCard.title }}</div>
                <div v-if="m.confirmCard.rows && m.confirmCard.rows.length" class="confirm-rows">
                  <div v-for="(r, ri) in m.confirmCard.rows" :key="ri" class="confirm-row">
                    <span class="confirm-k">{{ r.key }}</span>
                    <span class="confirm-v">{{ r.value }}</span>
                  </div>
                </div>
                <div v-if="m.confirmCard.state === 'pending'" class="confirm-actions">
                  <label class="confirm-allow" title="勾选后本会话内同类写操作不再弹卡，直接执行">
                    <input
                      v-model="m.confirmCard.alwaysAllow"
                      type="checkbox"
                      class="confirm-allow-box"
                    />
                    <span>始终允许此类操作</span>
                  </label>
                  <button class="confirm-btn primary" @click="onConfirmWrite(m.confirmCard)">确认办理</button>
                  <button class="confirm-btn" @click="onCancelWrite(m.confirmCard)">取消</button>
                  <span class="confirm-ttl">{{ m.confirmCard.remain ?? m.confirmCard.ttlSeconds }} 秒内有效</span>
                </div>
                <div v-else-if="m.confirmCard.state === 'submitting'" class="confirm-state">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  <span>正在办理…</span>
                </div>
                <div v-else-if="m.confirmCard.state === 'confirmed'" class="confirm-state ok">
                  <el-icon><Check /></el-icon>
                  <span>已确认并执行</span>
                </div>
                <div v-else-if="m.confirmCard.state === 'cancelled'" class="confirm-state muted">
                  <el-icon><Close /></el-icon>
                  <span>已取消本次操作</span>
                </div>
                <div v-else class="confirm-state err">
                  <el-icon><Warning /></el-icon>
                  <span>{{ m.confirmCard.error || '授权已失效，请重新发起' }}</span>
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

              <!-- 等待指示 -->
              <div v-if="m.pending && !m.reasoningSegments.length && !m.toolEvents.length && !m.content" class="thinking">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                <span class="thinking-text">正在思考…</span>
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
        placeholder="输入您的问题，Enter 发送"
        resize="none"
        @keydown.enter.exact.prevent="send"
      />
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
import { ref, reactive, nextTick, onBeforeUnmount } from 'vue'
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

// AI 检索联动：queryBooks 等工具返回的图书列表写入该 store，图书检索页 watch 联动
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
  try {
    const res = await aiApi.confirmWrite(card.token, card.tool, card.conversationId, card.alwaysAllow === true)
    card.state = 'confirmed'
    card.result = res
    const tip = res?.message || '操作已完成'
    if (assistantMsg) {
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${tip}` : tip
      assistantMsg.html = renderMarkdown(assistantMsg.content)
      scrollToBottom()
    }
    // M8 确认续跑：回执展示后自动发起下一轮，模型继续任务链剩余步骤
    continueAfterConfirm(card.tool)
  } catch (e) {
    // 失败分类：令牌类失效（过期/已被处理/不匹配）→ expired；业务错误 → failed。
    // 令牌一次性，失败后原卡无法重试，两种状态都只说明结果，不一律谎称过期
    const message = e?.message || '授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
  }
}

// 用户取消：卡片原地转为静默状态
const onCancelWrite = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
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
    const tip = res?.message || '批量办理完成'
    if (assistantMsg) {
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${tip}` : tip
      assistantMsg.html = renderMarkdown(assistantMsg.content)
      scrollToBottom()
    }
    // M8 确认续跑：批量办理完成后自动发起下一轮
    continueAfterConfirm(`批量办理 ${res?.successCount ?? 0} 项成功`)
  } catch (e) {
    const message = e?.message || '批量授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
  }
}

// 取消本批：批次到期自动失效，本地置灰即可
const onCancelBatch = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
}

// 授权过期：卡片灰态保留，说明发生过什么而非凭空消失
const onConfirmExpired = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'expired'
  card.error = '授权已过期，请重新发起该操作'
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
    return arr.map((rec) => ({
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

const send = async () => {
  if (generating.value) return
  if (!input.value.trim()) return
  const content = input.value.trim()
  input.value = ''
  await sendInternal(content, { continuation: false })
}

/**
 * M8 确认续跑：授权确认成功后自动发起的下一轮对话（面板内与助手页行为一致）。
 * 引导语以系统续跑身份发送（不显示用户气泡），模型从系统回执读到结果继续任务链。
 */
const continueAfterConfirm = async (toolSummary) => {
  if (generating.value || !currentConvId) return
  const guide = `授权已确认并执行完成（${toolSummary}）。请检查任务链中还有哪些步骤未完成，继续执行剩余步骤；若全部完成，请向用户汇总各项办理结果。`
  await sendInternal(guide, { continuation: true })
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
    // 写操作授权卡（SSE confirm 事件）
    confirmCard: null,
    // M7 批量授权卡（SSE batch_confirm 事件，batch 档位）
    batchConfirmCard: null,
    // M4：Agent 决策时间线
    timeline: [],
    createTime: new Date().toISOString(),
  })
  messages.value.push(assistantMsg)
  scrollToBottom()

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
        // 知识库来源：在调用 LLM 之前推送（早于 delta），按「标题 + 摘要」去重后累计
        msg.pending = false
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
          msg.confirmCard = {
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
          }
          // M4：授权请求记入时间线
          msg.timeline.push({ kind: 'confirm', tool: info.tool || '' })
          msg.content = msg.content || '请确认上方授权请求后继续。'
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.confirmCard)
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
        // 流结束：本轮既无写操作（confirm 事件）也无写意图（用户原话）才广播检索候补到馆藏图书页
        if (pendingAiSearch) {
          if (!turnHasWrite && !turnWriteIntent) {
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
    stopConfirmCountdown(m.confirmCard)
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
.input-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
