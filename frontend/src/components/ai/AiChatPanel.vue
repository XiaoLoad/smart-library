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

              <!-- 工具调用卡片 -->
              <div v-if="m.toolEvents && m.toolEvents.length" class="tool-events">
                <div v-for="(t, ti) in m.toolEvents" :key="ti" class="tool-card" :class="t.phase">
                  <el-icon class="tool-icon">
                    <Loading v-if="t.phase === 'start'" />
                    <Check v-else-if="t.phase === 'end'" />
                    <Close v-else-if="t.phase === 'error'" />
                  </el-icon>
                  <span class="tool-name">{{ toolLabel(t.name) }}</span>
                </div>
              </div>

              <!-- 写操作授权卡（浮动面板为简化版：空间有限，只留标题与操作区） -->
              <div v-if="m.confirmCard" class="confirm-card" :class="m.confirmCard.state">
                <div class="confirm-eyebrow">
                  <el-icon><Lock /></el-icon>
                  <span>{{ confirmEyebrow(m.confirmCard.state) }}</span>
                </div>
                <div class="confirm-title">{{ m.confirmCard.title }}</div>
                <div v-if="m.confirmCard.state === 'pending'" class="confirm-actions">
                  <button class="confirm-btn primary" @click="onConfirmWrite(m.confirmCard)">确认办理</button>
                  <button class="confirm-btn" @click="onCancelWrite(m.confirmCard)">取消</button>
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
 * 精简版 UI（无会话侧栏、无 RAG 药丸、无工具数据展开、无锚点导航）。
 * 挂载后懒初始化（init），流式期间组件被 v-show 隐藏不销毁——后台继续输出。
 */
import { ref, reactive, nextTick, onBeforeUnmount } from 'vue'
import { Loading, Check, Close, Lock, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { aiApi } from '@/api'
import { useAiSearchStore } from '@/stores/aiSearch'
import AppIcon from '@/components/AppIcon.vue'

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
}
const confirmEyebrow = (state) => CONFIRM_EYEBROWS[state] || '需要你的授权'

// 确认执行：只回传令牌，参数由服务端保存，客户端无法篡改
const onConfirmWrite = async (card) => {
  if (!card || card.state !== 'pending') return
  card.state = 'submitting'
  try {
    const res = await aiApi.confirmWrite(card.token, card.tool)
    card.state = 'confirmed'
    card.result = res
    const tip = res?.message || '操作已完成'
    if (assistantMsg) {
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${tip}` : tip
      assistantMsg.html = renderMarkdown(assistantMsg.content)
      scrollToBottom()
    }
  } catch (e) {
    card.state = 'expired'
    card.error = e?.message || '授权已失效，请重新发起该操作'
  }
}

// 用户取消：卡片原地转为静默状态
const onCancelWrite = (card) => {
  if (!card || card.state !== 'pending') return
  card.state = 'cancelled'
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

const renderMarkdown = (content) => {
  if (!content) return ''
  try { return md.render(content) } catch { return md.utils.escapeHtml(content) }
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgListRef.value) msgListRef.value.scrollTop = msgListRef.value.scrollHeight
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
        toolEvents: [],
        reasoningSegments: [],
        reasoningOpen: false,
        reasoningStreaming: false,
        reasoningSeconds: null,
        pending: false,
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

const send = async () => {
  if (generating.value) return
  if (!input.value.trim()) return
  const content = input.value.trim()
  input.value = ''
  lastUserText.value = content

  if (!currentConvId) {
    try {
      const conv = await aiApi.createConversation()
      currentConvId = conv.id
    } catch {
      ElMessage.error('创建会话失败')
      return
    }
  }

  messages.value.push({
    id: Date.now(),
    role: 'user',
    content,
    createTime: new Date().toISOString(),
  })
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
    // 写操作授权卡（SSE confirm 事件）
    confirmCard: null,
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
      body: JSON.stringify({ conversationId: currentConvId, content }),
      signal: abortController.signal,
    })
    const ct = resp.headers.get('content-type') || ''
    if (!ct.includes('text/event-stream')) {
      let msg = 'AI 请求失败'
      try {
        const errBody = await resp.json()
        msg = errBody.message || msg
      } catch { /* 忽略 */ }
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
      if (event === 'thinking') {
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
            events.push({ name: info.name || '', phase: 'start' })
          } else {
            let target = null
            for (let i = events.length - 1; i >= 0; i--) {
              if (events[i].name === (info.name || '') && events[i].phase === 'start') { target = events[i]; break }
            }
            if (target) target.phase = info.phase
            else events.push({ name: info.name || '', phase: info.phase })
          }
          // AI 检索联动：工具返回的图书列表广播到 aiSearchStore，
          // 图书检索页 watch 到后切换为「AI 检索结果」视图。
          // 不限定工具名：只要快照含图书条目（items 数组且元素带 title）即广播，
          // 避免「推荐书籍」类请求被模型路由到 adminQueryStats 等工具时漏掉
          if (info.phase === 'end' && info.data) {
            try {
              const snap = JSON.parse(info.data)
              const items = snap.items
              if (Array.isArray(items) && items.length && items[0]?.title) {
                console.log('[AI联动] 广播检索结果', info.name, items.length, '条')
                aiSearchStore.setResult(lastUserText.value || '', items, snap.total)
              }
            } catch (e) { console.warn('[AI联动] 快照解析失败', info.name, e) }
          }
        } catch { /* 忽略 */ }
        scrollToBottom()
      } else if (event === 'confirm') {
        // 写操作授权请求：模型发起写操作被门禁拦截，改为向用户请求授权
        msg.pending = false
        try {
          const info = JSON.parse(data)
          msg.confirmCard = {
            token: info.token,
            tool: info.tool,
            title: info.title || '写操作授权',
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 60,
            state: 'pending',
            result: null,
            error: '',
          }
          msg.content = msg.content || '请确认上方授权请求后继续。'
          msg.html = renderMarkdown(msg.content)
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
        if (event === 'error') {
          msg.content = `⚠️ ${data || 'AI 请求出错'}`
          msg.html = renderMarkdown(msg.content)
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
        assistantMsg.content = `⚠️ ${e.message || 'AI 请求失败'}`
        assistantMsg.html = renderMarkdown(assistantMsg.content)
      }
    }
  } finally {
    abortController = null
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

onBeforeUnmount(destroy)

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
