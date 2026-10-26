<template>
  <div class="ai-page">
    <!-- 会话列表侧栏 -->
    <aside class="conv-side">
      <div class="conv-head">
        <el-button type="primary" :icon="Plus" class="new-btn" @click="newConv">新的对话</el-button>
      </div>
      <div class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === currentConvId }"
          @click="switchConv(c.id)"
        >
          <el-icon class="conv-icon"><ChatDotRound /></el-icon>
          <div class="conv-meta">
            <div class="conv-title">{{ c.title }}</div>
            <div class="conv-time">{{ formatTime(c.createTime) }}</div>
          </div>
          <el-icon class="conv-del" title="删除会话" @click.stop="deleteConv(c.id)">
            <Delete />
          </el-icon>
        </div>
      </div>
    </aside>

    <!-- 对话主区：三段式 flex（头部固定 / 消息滚动 / 输入固定） -->
    <main class="chat-main">
      <header class="chat-head">
        <div class="head-id">
          <span class="eyebrow">智 · AI</span>
          <h2 class="chat-title">智能助手</h2>
        </div>
        <div class="head-tip">
          <el-icon><MagicStick /></el-icon>
          <span>可查询图书、借阅、个人数据</span>
        </div>
      </header>

      <!-- 唯一滚动容器：flex:1 + min-height:0，高度被钉死，输入框永不被顶走 -->
      <div class="msg-scroll" ref="msgListRef" @scroll="onScroll">
        <div class="msg-inner">
          <!-- 欢迎屏 -->
          <div v-if="!messages.length" class="welcome">
            <div class="welcome-mark">智</div>
            <h3 class="welcome-title">您好，请问需要什么帮助？</h3>
            <p class="welcome-desc">我可以为您查询馆藏图书、个人借阅、罚款记录等。</p>
            <div class="quick-prompts">
              <button v-for="p in quickPrompts" :key="p" class="quick-prompt" @click="sendPrompt(p)">
                <span class="quick-dot"></span>{{ p }}
              </button>
            </div>
          </div>

          <template v-for="(m, i) in messages" :key="m.id">
            <!-- 日期分隔线：历史对话的导航锚点 -->
            <div v-if="needDivider(i)" class="day-divider">
              <span class="day-line"></span>
              <span class="day-label">{{ dayLabel(m.createTime) }}</span>
              <span class="day-line"></span>
            </div>

            <!-- 用户消息：右侧气泡（data-anchor 供对话锚点导航定位与判定） -->
            <div v-if="m.role === 'user'" class="msg-row user" :data-anchor="String(m.id)">
              <div class="user-bubble">{{ m.content }}</div>
            </div>

            <!-- 助手消息：左侧头像 + 空气化排版 -->
            <div v-else class="msg-row assistant">
              <div class="msg-avatar"><span>智</span></div>
              <div class="msg-body">
                <!-- 深度思考（思维链）面板：思考中流式展开，正文开始后自动折叠 -->
                <div
                  v-if="m.reasoningSegments && m.reasoningSegments.length"
                  class="reason-panel"
                >
                  <button class="reason-head" @click="m.reasoningOpen = !m.reasoningOpen">
                    <span class="reason-mark">
                      <span v-if="m.reasoningStreaming" class="reason-dot"></span>
                      <AppIcon v-else name="sparkles" :size="13" />
                    </span>
                    <span class="reason-title">{{ m.reasoningStreaming ? '深度思考中' : '已深度思考' }}</span>
                    <span v-if="m.reasoningSeconds != null" class="reason-time">{{ m.reasoningSeconds }}s</span>
                    <span class="reason-chevron" :class="{ open: m.reasoningOpen }">
                      <AppIcon name="chevron-down" :size="12" />
                    </span>
                  </button>
                  <transition name="reason-expand">
                    <div v-if="m.reasoningOpen" class="reason-body">
                      <template v-for="(seg, si) in m.reasoningSegments" :key="si">
                        <div v-if="si > 0" class="reason-sep"><span></span></div>
                        <div class="reason-seg">{{ seg }}</div>
                      </template>
                    </div>
                  </transition>
                </div>

                <!-- 工具调用过程卡片 -->
                <div v-if="m.toolEvents && m.toolEvents.length" class="tool-events">
                  <!-- 多工具串联计数：调用 ≥ 2 个时显示链路概览 -->
                  <div v-if="m.toolEvents.length >= 2" class="tool-chain-head">
                    <el-icon class="chain-icon"><Connection /></el-icon>
                    <span>本次共调用 {{ m.toolEvents.length }} 个工具</span>
                  </div>
                  <div v-for="(t, ti) in m.toolEvents" :key="ti" class="tool-card" :class="t.phase">
                    <!-- 紧凑 pill 头部：序号 + 图标 + 名称 + 摘要 + 展开按钮 -->
                    <div class="tool-head">
                      <span class="tool-seq">{{ ti + 1 }}</span>
                      <el-icon class="tool-icon">
                        <Loading v-if="t.phase === 'start'" />
                        <Check v-else-if="t.phase === 'end'" />
                        <Close v-else-if="t.phase === 'error'" />
                      </el-icon>
                      <span class="tool-name">{{ toolLabel(t.name) }}</span>
                      <span v-if="t.detail" class="tool-detail">{{ t.detail }}</span>
                      <button
                        v-if="t.phase === 'end' && t.data"
                        class="tool-toggle"
                        :class="{ active: t.expanded }"
                        :title="t.expanded ? '折叠数据' : '查看返回数据'"
                        @click="t.expanded = !t.expanded"
                      >
                        <el-icon><ArrowDownBold v-if="!t.expanded" /><ArrowUpBold v-else /></el-icon>
                      </button>
                    </div>
                    <!-- 展开面板：独立于 pill 之外，从下方滑出的抽屉 -->
                    <transition name="tool-expand">
                      <div v-if="t.expanded && t.data" class="tool-data">
                        <div class="tool-data-label">
                          <el-icon><Document /></el-icon>
                          <span>返回数据快照</span>
                        </div>
                        <pre>{{ formatJson(t.data) }}</pre>
                      </div>
                    </transition>
                  </div>
                </div>

                <!-- 参考资料：本轮注入的知识库来源。
                     后端 rag 事件在调用 LLM 之前推送（早于 delta），故参考资料条先出、正文随后流式出现；
                     点击药丸展开片段摘要（后端只回传截断摘要，不落库，历史消息无此块） -->
                <div v-if="m.ragSources && m.ragSources.length" class="rag-block">
                  <div class="rag-row">
                    <span class="rag-label">
                      <AppIcon name="knowledge" :size="13" />
                      <span>参考资料</span>
                    </span>
                    <button
                      v-for="(s, si) in m.ragSources"
                      :key="si"
                      class="rag-chip"
                      :class="{ active: m.ragOpenIndex === si }"
                      :title="m.ragOpenIndex === si ? '收起片段' : '查看片段摘要'"
                      @click="m.ragOpenIndex = m.ragOpenIndex === si ? -1 : si"
                    >
                      <AppIcon name="book" :size="12" />
                      <span class="rag-title">{{ s.docTitle }}</span>
                    </button>
                  </div>
                  <transition name="tool-expand">
                    <div v-if="m.ragOpenIndex >= 0 && m.ragSources[m.ragOpenIndex]" class="rag-snippet">
                      <div class="tool-data-label">
                        <AppIcon name="knowledge" :size="12" />
                        <span>{{ m.ragSources[m.ragOpenIndex].docTitle }} · 检索片段摘要</span>
                      </div>
                      <p>{{ m.ragSources[m.ragOpenIndex].snippet }}</p>
                    </div>
                  </transition>
                </div>

                <div class="msg-content markdown" v-html="m.html"></div>

                <!-- 等待中：已建消息占位但尚无任何可见产出（思考未开始/检索中/工具间隙），
                     点状跳动提示避免「参考资料之后就没动静」的空窗；有正文或思考流后立即消失 -->
                <div
                  v-if="m.pending && !m.reasoningSegments.length && !m.toolEvents.length && !(m.ragSources && m.ragSources.length)"
                  class="msg-content thinking"
                >
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                  <span class="thinking-text">正在思考…</span>
                </div>
                <div
                  v-else-if="m.pending && (m.ragSources && m.ragSources.length) && !m.reasoningSegments.length && !m.toolEvents.length && !m.content"
                  class="msg-content thinking"
                >
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                  <span class="thinking-text">正在结合参考资料思考…</span>
                </div>
              </div>
            </div>
          </template>

          <!-- 底部留白，保证最后一条不被输入框遮挡 -->
          <div class="msg-bottom-gap"></div>
        </div>
      </div>

      <!-- 对话锚点导航：按提问轮次生成刻度，悬停看完整问题，点击定位 -->
      <ConversationNav
        :items="anchors"
        :scroll-el="msgListRef"
        :at-bottom="isAutoFollow"
        @jump="onJumpAnchor"
      />

      <!-- 「回到最新」浮动按钮：锚定在输入框上方，不随内容滚动 -->
      <transition name="jump">
        <button v-if="showJumpBtn" class="jump-latest" @click="scrollToBottomForce">
          <el-icon><ArrowDownBold /></el-icon>
          回到最新
        </button>
      </transition>

      <!-- 输入区：固定在视口底部 -->
      <div class="composer">
        <div class="composer-box" :class="{ active: input }">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            placeholder="输入您的问题，Enter 发送，Shift+Enter 换行"
            resize="none"
            class="composer-input"
            @keydown.enter.exact.prevent="send"
          />
          <button
            class="composer-btn"
            :class="thinking ? 'stop' : 'send'"
            :title="thinking ? '停止生成' : '发送'"
            @click="thinking ? stopGenerate() : send()"
          >
            <el-icon><VideoPause v-if="thinking" /><Promotion v-else /></el-icon>
          </button>
        </div>
        <p class="composer-tip">AI 助手基于馆藏数据回答，内容仅供参考。</p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import {
  Plus, ChatDotRound, MagicStick, Promotion, Delete,
  VideoPause, Loading, Check, Close, ArrowDownBold, ArrowUpBold, Connection, Document,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { aiApi } from '@/api'
import { useAiSearchStore } from '@/stores/aiSearch'
import ConversationNav from '@/components/ConversationNav.vue'
import AppIcon from '@/components/AppIcon.vue'

// keep-alive 缓存名单按路由 name 匹配，故显式声明组件名
defineOptions({ name: 'AIChat' })

// 初始化 Markdown 渲染器：启用代码高亮、链接安全属性
const md = new MarkdownIt({
  html: false,        // 不允许内嵌 HTML（防止 XSS）
  breaks: true,       // 单换行转 <br>
  linkify: true,      // 自动识别链接
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch {
        // fallthrough
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
  },
})

// 工具名称中文映射
const TOOL_LABELS = {
  // 通用工具（读者 + 管理员）
  queryCategories: '查询图书分类',
  queryBooks: '检索馆藏图书',
  queryHotBooks: '热门图书排行',
  queryAnnouncements: '查询公告',
  // 读者工具
  queryMyBorrows: '查询借阅记录',
  queryMyFines: '查询罚款记录',
  queryBorrowQuota: '查询借阅额度',
  queryMyNotifications: '查询通知消息',
  applyBorrow: '提交借阅申请',
  renewMyBorrow: '续借图书',
  requestReturn: '申请归还图书',
  // 管理员工具
  adminQueryBorrows: '查询全馆借阅',
  adminQueryFines: '查询全馆罚款',
  adminQueryUsers: '查询读者信息',
  adminQueryStats: '馆藏统计概览',
  adminCounterProcess: '柜台办理业务',
}
const toolLabel = (name) => TOOL_LABELS[name] || name

/**
 * 格式化工具返回的 JSON 数据快照用于展示。
 * 后端推送的 data 已是 JSON 字符串，这里尝试解析后美化输出，失败则原样返回。
 * @param {string} dataJson 后端推送的 JSON 字符串（可能已截断带省略号）
 */
const formatJson = (dataJson) => {
  if (!dataJson) return ''
  try {
    // 截断快照末尾的 "…" 需先去除再解析
    const trimmed = dataJson.replace(/…$/, '')
    const obj = JSON.parse(trimmed)
    return JSON.stringify(obj, null, 2)
  } catch {
    // 解析失败（如已截断）原样返回
    return dataJson
  }
}

const renderMarkdown = (content) => {
  if (!content) return ''
  try {
    return md.render(content)
  } catch {
    return md.utils.escapeHtml(content)
  }
}

// 历史/分页消息映射：预渲染 Markdown 为 html 缓存，避免模板内反复重渲染
const enrichMsg = (m) => ({
  ...m,
  html: renderMarkdown(m.content),
  toolEvents: [], // 历史消息无工具调用记录
  // 历史消息不持久化思维链（与工具中间态一致），仅本轮流式生成时展示
  reasoningSegments: [],
  reasoningOpen: false,
  reasoningStreaming: false,
  // 参考资料同理：rag 事件只在当轮推送，落库内容不含来源，历史消息无此块
  ragSources: [],
  ragOpenIndex: -1,
})

// 日期分隔（历史导航锚点）
const dayKey = (t) => (t ? String(t).slice(0, 10) : '')
const dayLabel = (t) => {
  const key = dayKey(t)
  if (!key) return ''
  const today = new Date()
  const fmt = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const yest = new Date(today); yest.setDate(today.getDate() - 1)
  if (key === fmt(today)) return '今天'
  if (key === fmt(yest)) return '昨天'
  const [y, mo, d] = key.split('-').map(Number)
  return y === today.getFullYear() ? `${mo}月${d}日` : `${y}年${mo}月${d}日`
}
// 与上一条消息不同日（或为第一条）时显示分隔线
const needDivider = (i) => {
  if (i === 0) return true
  return dayKey(messages.value[i].createTime) !== dayKey(messages.value[i - 1].createTime)
}

const conversations = ref([])
const currentConvId = ref(null)
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgListRef = ref(null)

// AI 检索联动：与悬浮球面板一致，queryBooks/queryHotBooks 结果广播到 aiSearchStore，
// 图书检索页 watch 到后切换为「AI 检索结果」视图
const aiSearchStore = useAiSearchStore()
const lastUserText = ref('')
// 历史分页加载状态
const oldestLoadedId = ref(null)   // 当前已加载消息中最老的 id（更早一页的游标）
const loadedCount = ref(0)         // 已加载消息数
const totalMessages = ref(0)       // 会话消息总数
const hasMore = ref(false)         // 是否还有更早消息
const loadingOlder = ref(false)    // 防重复拉取守卫
// 当前流式请求的 AbortController（用于取消）
let abortController = null
// 当前流式回复的助手消息引用（send 中创建，stopGenerate/catch/finally 中复用）
let assistantMsg = null

// 智能滚动控制
const isAutoFollow = ref(true)
const showJumpBtn = ref(false)

// 程序滚动抑制窗口：代码赋值 scrollTop 触发的 scroll 事件回调执行时，
// DOM 可能又增高（流式渲染），会被误判为"用户上翻中"而关闭自动跟随。
// 赋值前打时间戳，窗口内的 scroll 事件不参与跟随判定。
let programmaticScrollUntil = 0
const suppressScrollUntil = () => {
  programmaticScrollUntil = performance.now() + 100
}

const isAtBottom = () => {
  const el = msgListRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

const onScroll = () => {
  if (performance.now() < programmaticScrollUntil) return // 程序滚动，非用户操作
  const atBottom = isAtBottom()
  isAutoFollow.value = atBottom
  showJumpBtn.value = !atBottom && messages.value.length > 0
  // 向上滚动到接近顶部时，懒加载更早的历史消息
  const el = msgListRef.value
  if (el && el.scrollTop < 300 && hasMore.value && !loadingOlder.value && !isAutoFollow.value) {
    loadOlder()
  }
}

const scrollToBottom = async () => {
  if (!isAutoFollow.value) {
    // 用户上翻中：显示「回到最新」，不打断阅读
    showJumpBtn.value = true
    return
  }
  await nextTick()
  if (msgListRef.value) {
    suppressScrollUntil()
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
  }
  showJumpBtn.value = false
}

const scrollToBottomForce = async () => {
  isAutoFollow.value = true
  await nextTick()
  if (msgListRef.value) {
    suppressScrollUntil()
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
  }
  showJumpBtn.value = false
}

const quickPrompts = [
  '我当前借了哪些书？',
  '查找哲学类图书',
  '本月借阅最多的书是什么？',
  '我有未缴罚款吗？',
]

// 对话锚点导航
// 一轮提问 = 一个锚点：序号 + 完整问题（卡片内按行截断展示）+ 时间
const anchorTime = (t) => (t ? String(t).slice(5, 16).replace('T', ' ') : '')
const anchors = computed(() => {
  let seq = 0
  return messages.value
    .filter((m) => m.role === 'user')
    .map((m) => ({
      key: String(m.id),
      seq: ++seq,
      text: String(m.content || '').replace(/\s+/g, ' ').trim(),
      time: anchorTime(m.createTime),
    }))
})

// 跳转到历史轮次后主动脱离自动跟随，否则流式增量会把用户拽回底部
const onJumpAnchor = () => {
  isAutoFollow.value = false
  showJumpBtn.value = true
}

const loadConversations = async () => {
  try {
    const data = await aiApi.conversationList()
    conversations.value = data.list || []
    if (conversations.value.length) {
      await switchConv(conversations.value[0].id)
    }
  } catch (e) {
    // 加载失败静默
  }
}

const switchConv = async (id) => {
  if (thinking.value) return // 流式输出中不切换
  currentConvId.value = id
  isAutoFollow.value = true
  try {
    const data = await aiApi.messageList(id)
    // 后端返回最新一页（id DESC），倒转为正序后再渲染
    const page = (data.list || []).map(enrichMsg).reverse()
    messages.value = page
    loadedCount.value = page.length
    totalMessages.value = data.total || page.length
    oldestLoadedId.value = page.length ? page[0].id : null
    hasMore.value = loadedCount.value < totalMessages.value
  } catch (e) {
    messages.value = []
    hasMore.value = false
  }
  await scrollToBottomForce()
}

// 向上懒加载更早的历史消息：prepend 后按 DOM 高度差保持视口位置，避免跳动
const loadOlder = async () => {
  if (loadingOlder.value || !hasMore.value || !currentConvId.value || !oldestLoadedId.value) return
  loadingOlder.value = true
  const el = msgListRef.value
  const prevHeight = el ? el.scrollHeight : 0
  const prevTop = el ? el.scrollTop : 0
  try {
    const data = await aiApi.messageList(currentConvId.value, { beforeId: oldestLoadedId.value })
    const older = (data.list || []).map(enrichMsg).reverse()
    if (!older.length) {
      hasMore.value = false
      return
    }
    messages.value = [...older, ...messages.value]
    loadedCount.value += older.length
    oldestLoadedId.value = older[0].id
    hasMore.value = loadedCount.value < (data.total || totalMessages.value)
  } catch (e) {
    // 加载失败静默
  } finally {
    await nextTick()
    if (el) {
      // 高度差法：内容前插后，把滚动条下移新增高度，使视口锚定的内容不跳动
      suppressScrollUntil()
      el.scrollTop = el.scrollHeight - prevHeight + prevTop
    }
    loadingOlder.value = false
  }
}

const newConv = async () => {
  if (thinking.value) return
  try {
    const conv = await aiApi.createConversation()
    conversations.value.unshift(conv)
    currentConvId.value = conv.id
    messages.value = []
    isAutoFollow.value = true
    showJumpBtn.value = false
  } catch (e) {
    ElMessage.error('创建会话失败')
  }
}

const deleteConv = async (id) => {
  try {
    await ElMessageBox.confirm('确认删除该会话？删除后不可恢复。', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await aiApi.deleteConversation(id)
    const idx = conversations.value.findIndex((c) => c.id === id)
    if (idx >= 0) conversations.value.splice(idx, 1)
    if (currentConvId.value === id) {
      currentConvId.value = null
      messages.value = []
      if (conversations.value.length) {
        await switchConv(conversations.value[0].id)
      }
    }
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const sendPrompt = (prompt) => {
  input.value = prompt
  send()
}

/** 停止生成：中断当前 SSE 请求 */
const stopGenerate = () => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  thinking.value = false
}

const send = async () => {
  if (thinking.value) return
  if (!input.value.trim()) return
  const content = input.value.trim()
  input.value = ''
  lastUserText.value = content

  // 无会话时先创建
  if (!currentConvId.value) {
    try {
      const conv = await aiApi.createConversation()
      conversations.value.unshift(conv)
      currentConvId.value = conv.id
    } catch (e) {
      ElMessage.error('创建会话失败')
      return
    }
  }

  const convId = currentConvId.value
  const conv = conversations.value.find((c) => c.id === convId)
  const isFirstMsg = !conv || conv.title === '新对话' || !conv.title

  // 推送用户消息
  messages.value.push({
    id: Date.now(),
    conversationId: convId,
    role: 'user',
    content,
    createTime: new Date().toISOString(),
  })
  isAutoFollow.value = true
  showJumpBtn.value = false
  await scrollToBottom()
  thinking.value = true
  scrollToBottom() // 思考占位渲染增高，保持视口在底部

  // SSE 流式请求
  abortController = new AbortController()

  // 立即创建助手消息气泡（pending=true 显示「正在思考…」跳动），
  // 不等首个 SSE 事件——避免首事件前的空窗期没有任何反馈
  assistantMsg = reactive({
    id: Date.now() + 1,
    conversationId: convId,
    role: 'assistant',
    content: '',
    html: '',
    toolEvents: [],
    // 深度思考：reasoningSegments 按「思考轮次」分段（工具调用会产生多段）；
    // reasoningStreaming 控制头部「思考中/已思考」与脉冲；reasoningOpen 控制折叠
    reasoningSegments: [],
    reasoningOpen: false,
    reasoningStreaming: false,
    reasoningSeconds: null,
    _reasonStartAt: 0,
    _reasonTimer: null,
    _reasonSegOpen: false,
    // 等待指示：true 期间显示「正在思考…」跳动；首个可见产出（思考/工具/正文）到达后置 false
    pending: true,
    // 知识库来源（SSE rag 事件），ragOpenIndex 为当前展开的片段序号，-1 表示全部收起
    ragSources: [],
    ragOpenIndex: -1,
    createTime: new Date().toISOString(),
  })
  messages.value.push(assistantMsg)
  scrollToBottom()

  // 思考耗时收尾：最后一段时间写入 reasoningSeconds 后停止计时（耗时保留展示）。
  // 定义在 try 之外：catch/finally 兜底路径也要调用（否则流中断时计时器泄漏）
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
      body: JSON.stringify({ conversationId: convId, content }),
      signal: abortController.signal,
    })

    // 非流式响应 → 后端抛 BusinessException，返回 JSON 错误
    const ct = resp.headers.get('content-type') || ''
    if (!ct.includes('text/event-stream')) {
      let msg = 'AI 请求失败'
      try {
        const errBody = await resp.json()
        msg = errBody.message || msg
      } catch {
        // 忽略
      }
      throw new Error(msg)
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    const ensureAssistantMsg = () => {
      // 气泡已在发送时创建（pending=true），这里只是返回引用；
      // 首个实际事件到达时关闭单独的「思考中」占位
      if (thinking.value) {
        thinking.value = false
      }
      return assistantMsg
    }

    const handleEvent = (block) => {
      const lines = block.split('\n')
      let event = 'message'
      const dataParts = []
      for (const line of lines) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          let d = line.slice(5)
          if (d.startsWith(' ')) d = d.slice(1)
          dataParts.push(d)
        }
      }
      const data = dataParts.join('\n')
      if (event === 'thinking') {
        // 深度思考思维链增量：流式追加到当前思考段，面板保持展开
        const msg = ensureAssistantMsg()
        msg.pending = false
        if (data) {
          if (!msg._reasonSegOpen) {
            // 上一段思维链已被工具调用/正文截断 → 新开一段（多轮工具调用场景）
            msg.reasoningSegments.push('')
            msg._reasonSegOpen = true
          }
          if (!msg.reasoningStreaming) {
            // 本段思考开始：启动耗时计时（0.1s 粒度，避免 setData 高频）
            msg.reasoningStreaming = true
            msg.reasoningOpen = true
            msg._reasonStartAt = Date.now()
            clearInterval(msg._reasonTimer)
            msg._reasonTimer = setInterval(() => {
              msg.reasoningSeconds = ((Date.now() - msg._reasonStartAt) / 1000).toFixed(1)
            }, 100)
          }
          const segs = msg.reasoningSegments
          segs[segs.length - 1] += data
          scrollToBottom()
        }
      } else if (event === 'rag') {
        // 知识库来源：在调用 LLM 之前推送（早于 delta），气泡内先出「参考资料」条
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const list = JSON.parse(data)
          if (Array.isArray(list)) {
            for (const item of list) {
              const docTitle = item?.docTitle || '未命名文档'
              const snippet = item?.snippet || ''
              // 多轮工具调用可能重复检索到同一片段，按「标题 + 摘要」去重，避免药丸堆积
              const dup = msg.ragSources.some(
                (s) => s.docTitle === docTitle && s.snippet === snippet
              )
              if (!dup) msg.ragSources.push({ docTitle, snippet })
            }
          }
        } catch {
          // 忽略解析失败的 rag 事件
        }
      } else if (event === 'delta') {
        const msg = ensureAssistantMsg()
        msg.pending = false
        // 正文开始：思维链阶段结束，停止耗时并自动折叠面板（DeepSeek 式交互）
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content += data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'reset') {
        // 进入工具调用轮：清空工具调用前的过渡文本（正文只保留最终回答，
        // 工具调用过程由工具卡片承载）；本段思维链结束，停止耗时并折叠面板
        const msg = ensureAssistantMsg()
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content = ''
        msg.html = ''
      } else if (event === 'final') {
        // 后端完成回答后的最终文本（已剔除误输出的工具调用代码），整体替换流式拼接内容
        const msg = ensureAssistantMsg()
        msg.content = data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'tool') {
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const info = JSON.parse(data)
          const events = msg.toolEvents
          if (info.phase === 'start') {
            // start：新增一张「调用中」卡片
            events.push({
              name: info.name || '',
              phase: 'start',
              detail: info.detail || '',
              data: null,
              expanded: false,
            })
          } else {
            // end / error：更新对应的 start 卡片（同名且仍在 start 状态的最后一条），
            // 而非新增卡片——否则 start 卡片会永远停在加载态
            let target = null
            for (let i = events.length - 1; i >= 0; i--) {
              if (events[i].name === (info.name || '') && events[i].phase === 'start') {
                target = events[i]
                break
              }
            }
            const endDetail = info.detail || ''
            if (target) {
              target.phase = info.phase // end → 绿色对勾，error → 红色叉，停止旋转
              if (endDetail) {
                target.detail = target.detail
                  ? `${target.detail} · ${endDetail}`
                  : endDetail
              }
              // 结构化输出：end 阶段携带数据快照，可展开查看
              if (info.phase === 'end' && info.data) {
                target.data = info.data
              }
            } else {
              events.push({
                name: info.name || '',
                phase: info.phase,
                detail: endDetail,
                data: info.phase === 'end' ? (info.data || null) : null,
                expanded: false,
              })
            }
            // AI 检索联动：工具返回的图书列表广播到 aiSearchStore，
            // 图书检索页 watch 到后切换为「AI 检索结果」视图（与悬浮球面板逻辑一致）。
            // 不限定工具名：只要快照含图书条目（items 数组且元素带 title）即广播
            if (info.phase === 'end' && info.data) {
              try {
                const snap = JSON.parse(info.data)
                const items = snap.items
                if (Array.isArray(items) && items.length && items[0]?.title) {
                  aiSearchStore.setResult(lastUserText.value || '', items, snap.total)
                }
              } catch { /* 快照异常不影响对话 */ }
            }
          }
        } catch {
          // 忽略解析失败的 tool 事件
        }
        scrollToBottom()
      } else if (event === 'done') {
        thinking.value = false
        if (assistantMsg) {
          assistantMsg.pending = false
          stopReasonTimer(assistantMsg)
          assistantMsg._reasonSegOpen = false
          assistantMsg.reasoningStreaming = false
          assistantMsg.reasoningOpen = false
        }
        if (isFirstMsg && conv) {
          conv.title = content.length > 20 ? content.substring(0, 20) + '…' : content
        }
      } else if (event === 'error') {
        thinking.value = false
        const msg = ensureAssistantMsg()
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content = `⚠️ ${data || 'AI 请求出错'}`
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
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
    thinking.value = false
    if (e.name === 'AbortError') {
      // 用户主动停止：保留已生成内容
      return
    }
    ElMessage.error(e.message || 'AI 请求失败')
    if (assistantMsg) {
      assistantMsg.content = `⚠️ ${e.message || 'AI 请求失败'}`
    }
  } finally {
    abortController = null
    // 流结束（完成/停止/出错）后，仍在加载态的工具卡片停止旋转
    if (assistantMsg && assistantMsg.toolEvents) {
      assistantMsg.toolEvents.forEach((t) => {
        if (t.phase === 'start') t.phase = 'end'
      })
    }
    // 思维链状态兜底：中断/异常时停止计时、停止「思考中」脉冲并折叠
    if (assistantMsg) {
      assistantMsg.pending = false
      stopReasonTimer(assistantMsg)
      assistantMsg._reasonSegOpen = false
      assistantMsg.reasoningStreaming = false
    }
  }
}

const formatTime = (t) => {
  if (!t) return ''
  return t.substring(5, 10)
}

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
})

onMounted(loadConversations)
</script>

<style scoped>
/* 布局：高度钉死，仅消息区滚动 */
.ai-page {
  display: grid;
  grid-template-columns: 280px 1fr;
  grid-template-rows: 1fr;          /* 显式 1fr 行，约束子项不随内容增高 */
  height: calc(100vh - var(--header-height));
  overflow: hidden;
}

/* 会话侧栏 */
.conv-side {
  background: var(--color-bg-elevated);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.conv-head {
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
  flex-shrink: 0;
}

.new-btn { width: 100%; }

.conv-list {
  flex: 1;
  min-height: 0;                    /* flex 滚动关键 */
  overflow-y: auto;
  padding: var(--space-3);
}

.conv-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-soft);
  margin-bottom: var(--space-1);
}
.conv-item:hover { background: var(--color-bg-subtle); }
.conv-item.active { background: var(--color-primary-light); }

.conv-icon { margin-top: 2px; color: var(--color-text-tertiary); }
.conv-item.active .conv-icon { color: var(--color-primary); }

.conv-del {
  position: absolute;
  top: var(--space-3);
  right: var(--space-3);
  color: var(--color-text-tertiary);
  opacity: 0;
  transition: all var(--duration-fast) var(--ease-soft);
  padding: 2px;
  border-radius: var(--radius-sm);
}
.conv-item:hover .conv-del { opacity: 1; }
.conv-del:hover {
  color: var(--color-danger);
  background: rgba(168, 90, 74, 0.1);
}

.conv-meta { flex: 1; min-width: 0; padding-right: 20px; }
.conv-title {
  font-size: var(--fs-small);
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 2px;
}
.conv-item.active .conv-title { color: var(--color-primary); font-weight: 500; }
.conv-time {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 对话主区 */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
  /* 微妙纸感：顶部一抹暖光，营造空间纵深 */
  background:
    radial-gradient(120% 60% at 50% -10%, rgba(58, 90, 107, 0.05), transparent 60%),
    var(--color-bg);
}

.chat-head {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elevated);
  z-index: 2;
}

.head-id .chat-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  margin-top: 2px;
}

.head-tip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 消息滚动区（唯一滚动容器） */
.msg-scroll {
  flex: 1;
  min-height: 0;                    /* flex 滚动关键：允许收缩触发 overflow */
  overflow-y: auto;
  overflow-x: hidden;
  /* 锚点定位时在顶部留出呼吸位，问题不会紧贴容器边缘 */
  scroll-padding-top: var(--space-4);
}

.msg-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-6) 0;
}

.msg-bottom-gap { height: var(--space-4); }

/* 欢迎屏 */
.welcome {
  text-align: center;
  padding: var(--space-8) var(--space-5) var(--space-6);
  max-width: 560px;
  margin: 0 auto;
}

.welcome-mark {
  width: 60px;
  height: 60px;
  margin: 0 auto var(--space-5);
  border-radius: var(--radius-lg);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-size: 1.9rem;
  font-weight: 600;
  box-shadow: var(--shadow-md);
}

.welcome-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h2);
  font-weight: 600;
  margin-bottom: var(--space-3);
}
.welcome-desc {
  color: var(--color-text-secondary);
  margin-bottom: var(--space-6);
}

.quick-prompts {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  text-align: left;
}
.quick-prompt {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  transition: all var(--duration-fast) var(--ease-soft);
}
.quick-prompt:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
  color: var(--color-primary);
  transform: translateX(2px);
}
.quick-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-primary);
  flex-shrink: 0;
  opacity: 0.5;
}

/* 日期分隔线 */
.day-divider {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin: var(--space-6) 0 var(--space-5);
}
.day-line {
  flex: 1;
  height: 1px;
  background: var(--color-divider);
}
.day-label {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
  white-space: nowrap;
  font-family: var(--font-serif);
}

/* 消息行 */
.msg-row {
  display: flex;
  margin-bottom: var(--space-5);
  animation: msg-in var(--duration) var(--ease-out);
}
@keyframes msg-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 用户：右对齐气泡 */
.msg-row.user {
  justify-content: flex-end;
}
.user-bubble {
  max-width: 78%;
  background: var(--color-primary);
  color: var(--color-text-inverse);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-lg) var(--radius-lg) var(--radius-sm) var(--radius-lg);
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: var(--shadow-sm);
}

/* 助手：左对齐头像 + 内容 */
.msg-row.assistant {
  gap: var(--space-3);
  justify-content: flex-start;
}
.msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-small);
  background: var(--color-primary);
  color: var(--color-text-inverse);
}
.msg-body {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 46px);
}

/* 助手消息：空气化排版，不套重卡片 */
.msg-content {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text);
  padding: var(--space-1) 0;
  word-break: break-word;
}
.msg-content.thinking {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-tertiary);
}
.thinking-text {
  font-size: var(--fs-caption);
  margin-left: 2px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  animation: pulse 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
@keyframes pulse {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* 深度思考（思维链）面板 */
.reason-panel {
  margin-bottom: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  overflow: hidden;
}
.reason-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  transition: background var(--duration-fast) var(--ease-soft);
}
.reason-head:hover { background: var(--color-bg-muted); }
.reason-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-primary-light);
  color: var(--color-primary);
  flex-shrink: 0;
}
.reason-title {
  font-family: var(--font-serif);
  font-weight: 600;
  letter-spacing: 0.02em;
}
/* 思考耗时（如 12.4s）：等宽数字避免跳动 */
.reason-time {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
  opacity: 0.85;
}
.reason-chevron {
  margin-left: auto;
  display: inline-flex;
  transition: transform var(--duration-fast) var(--ease-soft);
  color: var(--color-text-tertiary);
}
.reason-chevron.open { transform: rotate(180deg); }

/* 思考中脉冲点（与等待态 .dot 同款呼吸节奏，尺寸更小） */
.reason-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: reason-pulse 1.2s infinite ease-in-out both;
}
@keyframes reason-pulse {
  0%, 80%, 100% { transform: scale(0.7); opacity: 0.45; }
  40% { transform: scale(1.15); opacity: 1; }
}

/* 思维链正文：小号次级文字，限高内滚，避免超长推理撑爆对话区 */
.reason-body {
  padding: 0 12px 10px 38px;
  max-height: 240px;
  overflow-y: auto;
  font-size: var(--fs-caption);
  line-height: 1.75;
  color: var(--color-text-secondary);
}
.reason-seg {
  white-space: pre-wrap;
  word-break: break-word;
}
/* 多轮思考之间的分隔（工具调用后再次思考） */
.reason-sep {
  display: flex;
  align-items: center;
  margin: 8px 0;
}
.reason-sep span {
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

/* 展开/折叠过渡：与工具数据抽屉同款动效 */
.reason-expand-enter-active,
.reason-expand-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft),
              transform var(--duration-fast) var(--ease-soft);
  transform-origin: top;
}
.reason-expand-enter-from,
.reason-expand-leave-to {
  opacity: 0;
  transform: translateY(-4px) scaleY(0.96);
}

/* —— 参考资料（知识库检索来源） ——
   沿用工具卡片的视觉语言：纸色药丸 + 虚线下分隔，不新增视觉语言 */
.rag-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
}
.rag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.rag-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.rag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  border: 1px dashed var(--color-border);
  background: var(--color-bg-elevated);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}
.rag-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.rag-chip.active {
  border-style: solid;
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  color: var(--color-primary);
}
/* 文档名可能很长：单行省略，完整名称由 title 悬停给出 */
.rag-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-snippet {
  max-width: 560px;
  margin-top: var(--space-1);
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-bg-subtle);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
.rag-snippet p {
  margin: 6px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 工具调用卡片 */
.tool-events {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: var(--space-2);
}
/* 多工具串联计数头部 */
.tool-chain-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-subtle);
  border: 1px dashed var(--color-border);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: 2px;
}
.chain-icon { font-size: 12px; }

/* tool-card 是容器，不再承载 pill 样式；负责纵向堆叠 head + data */
.tool-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
}
/* pill 形头部：紧凑横排，永不变形 */
.tool-head {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 24px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  position: relative;
  transition: all var(--duration-fast) var(--ease-soft);
}
.tool-card.start .tool-head { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-primary-light); }
.tool-card.end .tool-head { border-color: var(--color-success); color: var(--color-success); }
.tool-card.error .tool-head { border-color: var(--color-danger); color: var(--color-danger); }
.tool-head .tool-icon { animation: spin 1s linear infinite; }
.tool-card.end .tool-head .tool-icon,
.tool-card.error .tool-head .tool-icon { animation: none; }
@keyframes spin { to { transform: rotate(360deg); } }
.tool-name { font-weight: 500; }
.tool-detail { color: var(--color-text-tertiary); }

/* 序号角标：调用顺序，钉在 pill 左侧 */
.tool-seq {
  position: absolute;
  left: 8px;
  top: 50%;
  transform: translateY(-50%);
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-bg-subtle);
  color: var(--color-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-family: var(--font-mono);
  line-height: 1;
  border: 1px solid var(--color-border);
}
.tool-card.start .tool-seq { background: var(--color-primary); color: var(--color-text-inverse); border-color: var(--color-primary); }
.tool-card.end .tool-seq { background: var(--color-success); color: var(--color-text-inverse); border-color: var(--color-success); }
.tool-card.error .tool-seq { background: var(--color-danger); color: #fff; border-color: var(--color-danger); }

/* 展开按钮 */
.tool-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 0;
  transition: all var(--duration-fast) var(--ease-soft);
}
.tool-toggle:hover,
.tool-toggle.active {
  background: var(--color-bg-subtle);
  color: var(--color-primary);
}

/* 展开面板：独立于 pill 之外的下沉抽屉，与 pill 视觉关联但边界分明 */
.tool-data {
  align-self: stretch;
  margin-top: 4px;
  margin-left: 14px;
  padding: 8px 12px 10px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  border-top: none;
  border-top-left-radius: 0;
  border-bottom-right-radius: var(--radius-md);
  overflow-x: auto;
  position: relative;
}
/* 抽屉左上角与 pill 的视觉连接线 */
.tool-data::before {
  content: '';
  position: absolute;
  left: -1px;
  top: -8px;
  width: 2px;
  height: 8px;
  background: var(--color-border);
}
.tool-data-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
  font-family: var(--font-mono);
}
.tool-data-label .el-icon { font-size: 11px; }
.tool-data pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 展开/折叠过渡动画 */
.tool-expand-enter-active,
.tool-expand-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft),
              transform var(--duration-fast) var(--ease-soft);
  transform-origin: top;
}
.tool-expand-enter-from,
.tool-expand-leave-to {
  opacity: 0;
  transform: translateY(-4px) scaleY(0.96);
}

/* Markdown 排版 */
.msg-content.markdown :deep(p) { margin: 0 0 var(--space-2); }
.msg-content.markdown :deep(p:last-child) { margin-bottom: 0; }
.msg-content.markdown :deep(h1),
.msg-content.markdown :deep(h2),
.msg-content.markdown :deep(h3),
.msg-content.markdown :deep(h4) {
  margin: var(--space-3) 0 var(--space-2);
  font-weight: 600;
  line-height: 1.4;
  font-family: var(--font-serif);
}
.msg-content.markdown :deep(h1) { font-size: 1.35em; }
.msg-content.markdown :deep(h2) { font-size: 1.2em; }
.msg-content.markdown :deep(h3) { font-size: 1.08em; }
.msg-content.markdown :deep(ul),
.msg-content.markdown :deep(ol) { margin: var(--space-2) 0; padding-left: 1.5em; }
.msg-content.markdown :deep(li) { margin: 4px 0; }
.msg-content.markdown :deep(code) {
  background: var(--color-bg-muted);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 0.88em;
}
.msg-content.markdown :deep(pre) {
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  overflow-x: auto;
  margin: var(--space-2) 0;
}
.msg-content.markdown :deep(pre code) { background: transparent; padding: 0; font-size: 0.85em; line-height: 1.5; }
.msg-content.markdown :deep(table) {
  border-collapse: collapse;
  margin: var(--space-2) 0;
  width: 100%;
  font-size: var(--fs-small);
}
.msg-content.markdown :deep(th),
.msg-content.markdown :deep(td) {
  border: 1px solid var(--color-border);
  padding: 6px 12px;
  text-align: left;
}
.msg-content.markdown :deep(th) { background: var(--color-bg-subtle); font-weight: 600; }
.msg-content.markdown :deep(blockquote) {
  border-left: 3px solid var(--color-primary);
  padding-left: var(--space-3);
  margin: var(--space-2) 0;
  color: var(--color-text-secondary);
}
.msg-content.markdown :deep(a) { color: var(--color-primary); text-decoration: none; }
.msg-content.markdown :deep(a:hover) { text-decoration: underline; }
.msg-content.markdown :deep(strong) { font-weight: 600; color: var(--color-text); }

/* 「回到最新」浮动按钮 */
.jump-latest {
  position: absolute;
  left: 50%;
  bottom: 148px;                         /* 锚定在输入盒上方，不随内容滚动 */
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 4px;
  background: var(--color-bg-elevated);
  color: var(--color-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  padding: 8px 16px;
  cursor: pointer;
  font-size: var(--fs-caption);
  font-weight: 500;
  box-shadow: var(--shadow-md);
  z-index: 6;
  transition: all var(--duration-fast) var(--ease-soft);
}
.jump-latest:hover {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-color: var(--color-primary);
  transform: translateX(-50%) translateY(-2px);
}
.jump-enter-active, .jump-leave-active {
  transition: all var(--duration) var(--ease-out);
}
.jump-enter-from, .jump-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px);
}

/* 输入区（固定底部） */
.composer {
  flex-shrink: 0;
  padding: var(--space-3) var(--space-6) var(--space-4);
  background: linear-gradient(to top, var(--color-bg) 70%, transparent);
  z-index: 2;
}
.composer-box {
  position: relative;
  max-width: 860px;
  margin: 0 auto;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: border-color var(--duration-fast) var(--ease-soft),
              box-shadow var(--duration-fast) var(--ease-soft);
}
.composer-box:focus-within,
.composer-box.active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-light);
}
.composer-input :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent;
  padding: var(--space-3) var(--space-4);
  padding-right: 56px;             /* 给发送按钮留位 */
  font-size: var(--fs-body);
  line-height: var(--lh-normal);
  resize: none;
  box-shadow: none;
}
.composer-btn {
  position: absolute;
  right: 10px;
  bottom: 10px;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  transition: all var(--duration-fast) var(--ease-soft);
}
.composer-btn.send {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}
.composer-btn.send:hover { background: var(--color-primary-hover); transform: scale(1.05); }
.composer-btn.stop {
  background: var(--color-danger);
  color: #fff;
}
.composer-btn.stop:hover { transform: scale(1.05); }
.composer-tip {
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: var(--space-2);
}

/* 响应式 */
@media (max-width: 768px) {
  .ai-page { grid-template-columns: 1fr; grid-template-rows: 1fr; }
  .conv-side { display: none; }
  .msg-inner { padding: var(--space-4) var(--space-3) 0; }
  .composer { padding: var(--space-2) var(--space-3) var(--space-3); }
  .user-bubble { max-width: 88%; }
}
</style>
