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

    <!-- 对话主区 -->
    <main class="chat-main">
      <header class="chat-head">
        <div>
          <span class="eyebrow">智 · AI</span>
          <h2 class="chat-title">智能助手</h2>
        </div>
        <div class="head-tip">
          <el-icon><MagicStick /></el-icon>
          <span>可以查询图书、借阅、个人数据</span>
        </div>
      </header>

      <div class="msg-list" ref="msgListRef">
        <div v-if="!messages.length" class="welcome">
          <div class="welcome-mark">智</div>
          <h3 class="welcome-title">您好，请问需要什么帮助？</h3>
          <p class="welcome-desc">我可以为您查询馆藏图书、个人借阅、罚款记录等。</p>
          <div class="quick-prompts">
            <button v-for="p in quickPrompts" :key="p" class="quick-prompt" @click="sendPrompt(p)">
              {{ p }}
            </button>
          </div>
        </div>

        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row"
          :class="m.role"
        >
          <div class="msg-avatar">
            <span v-if="m.role === 'user'">我</span>
            <span v-else>智</span>
          </div>
          <div class="msg-body">
            <div class="msg-name">{{ m.role === 'user' ? '我' : '智能助手' }}</div>
            <div class="msg-content">
              <template v-if="m.role === 'assistant'">
                <span v-for="(line, i) in m.content.split('\n')" :key="i">{{ line }}<br v-if="i < m.content.split('\n').length - 1" /></span>
              </template>
              <template v-else>{{ m.content }}</template>
            </div>
          </div>
        </div>

        <div v-if="thinking" class="msg-row assistant">
          <div class="msg-avatar"><span>智</span></div>
          <div class="msg-body">
            <div class="msg-name">智能助手</div>
            <div class="msg-content thinking">
              <span class="dot"></span><span class="dot"></span><span class="dot"></span>
            </div>
          </div>
        </div>
      </div>

      <div class="input-area">
        <div class="input-wrap">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            placeholder="输入您的问题，Enter 发送，Shift+Enter 换行"
            resize="none"
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :icon="Promotion" @click="send" :loading="thinking">发送</el-button>
        </div>
        <div class="input-tip">
          AI 助手基于馆藏数据回答，请勿输入敏感信息。
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { Plus, ChatDotRound, MagicStick, Promotion, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '@/api'

const conversations = ref([])
const currentConvId = ref(null)
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgListRef = ref(null)
// 当前流式请求的 AbortController（用于取消）
let abortController = null

const quickPrompts = [
  '我当前借了哪些书？',
  '查找哲学类图书',
  '本月借阅最多的书是什么？',
  '我有未缴罚款吗？',
]

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
  try {
    const data = await aiApi.messageList(id)
    messages.value = data.list || []
  } catch (e) {
    messages.value = []
  }
  await scrollToBottom()
}

const newConv = async () => {
  if (thinking.value) return
  try {
    const conv = await aiApi.createConversation()
    conversations.value.unshift(conv)
    currentConvId.value = conv.id
    messages.value = []
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

const send = async () => {
  if (!input.value.trim() || thinking.value) return
  const content = input.value.trim()
  input.value = ''

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
  // 首条消息本地更新标题
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
  await scrollToBottom()
  thinking.value = true

  // SSE 流式请求
  abortController = new AbortController()
  let assistantMsg = null
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
      if (event === 'delta') {
        if (!assistantMsg) {
          thinking.value = false
          // 用 reactive 包装：局部变量引用 Proxy，修改其属性能触发响应式更新
          // （若用普通对象，push 到 ref 数组后局部变量引用的是原始对象，修改不触发更新）
          assistantMsg = reactive({
            id: Date.now() + 1,
            conversationId: convId,
            role: 'assistant',
            content: '',
            createTime: new Date().toISOString(),
          })
          messages.value.push(assistantMsg)
        }
        assistantMsg.content += data
        scrollToBottom()
      } else if (event === 'done') {
        thinking.value = false
        if (isFirstMsg && conv) {
          conv.title = content.length > 20 ? content.substring(0, 20) + '…' : content
        }
      } else if (event === 'error') {
        thinking.value = false
        if (!assistantMsg) {
          assistantMsg = reactive({
            id: Date.now() + 1,
            conversationId: convId,
            role: 'assistant',
            content: '',
            createTime: new Date().toISOString(),
          })
          messages.value.push(assistantMsg)
        }
        assistantMsg.content = `⚠️ ${data || 'AI 请求出错'}`
        scrollToBottom()
      }
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      // 按空行切分事件块
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (block.trim()) handleEvent(block)
      }
    }
    // 处理残留事件
    if (buffer.trim()) handleEvent(buffer)
  } catch (e) {
    thinking.value = false
    if (e.name === 'AbortError') return
    ElMessage.error(e.message || 'AI 请求失败')
    if (assistantMsg) {
      assistantMsg.content = `⚠️ ${e.message || 'AI 请求失败'}`
    }
  } finally {
    abortController = null
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgListRef.value) {
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
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
.ai-page {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: calc(100vh - var(--header-height));
}

/* 会话侧栏 */
.conv-side {
  background: var(--color-bg-elevated);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}

.conv-head {
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
}

.new-btn {
  width: 100%;
}

.conv-list {
  flex: 1;
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
  transition: all var(--duration-fast) var(--ease-soft);
  margin-bottom: var(--space-1);
}

.conv-item:hover {
  background: var(--color-bg-subtle);
}

.conv-item.active {
  background: var(--color-primary-light);
}

.conv-icon {
  margin-top: 2px;
  color: var(--color-text-tertiary);
}

.conv-item.active .conv-icon {
  color: var(--color-primary);
}

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

.conv-item:hover .conv-del {
  opacity: 1;
}

.conv-del:hover {
  color: var(--color-danger);
  background: var(--color-danger-light, rgba(255, 77, 79, 0.1));
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
  background: var(--color-bg);
  min-width: 0;
}

.chat-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elevated);
}

.chat-title {
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

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
}

/* 欢迎屏 */
.welcome {
  text-align: center;
  padding: var(--space-8) var(--space-5);
  max-width: 560px;
  margin: 0 auto;
}

.welcome-mark {
  width: 64px;
  height: 64px;
  margin: 0 auto var(--space-5);
  border-radius: var(--radius-lg);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-size: 2rem;
  font-weight: 600;
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
}

.quick-prompt {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  transition: all var(--duration-fast) var(--ease-soft);
  text-align: left;
}

.quick-prompt:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

/* 消息行 */
.msg-row {
  display: flex;
  gap: var(--space-3);
  max-width: 880px;
  margin: 0 auto var(--space-5);
}

.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-small);
}

.msg-row.user .msg-avatar {
  background: var(--color-secondary-light);
  color: var(--color-secondary);
}

.msg-row.assistant .msg-avatar {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.msg-body { flex: 1; min-width: 0; }

.msg-name {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-2);
  font-weight: 500;
}

.msg-content {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  white-space: pre-wrap;
}

.msg-row.user .msg-content {
  background: var(--color-primary-light);
  border-color: transparent;
  color: var(--color-text);
}

.msg-content.thinking {
  display: flex;
  gap: 4px;
  align-items: center;
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

/* 输入区 */
.input-area {
  padding: var(--space-4) var(--space-6);
  border-top: 1px solid var(--color-border);
  background: var(--color-bg-elevated);
}

.input-wrap {
  display: flex;
  gap: var(--space-3);
  max-width: 880px;
  margin: 0 auto;
  align-items: flex-end;
}

.input-tip {
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: var(--space-2);
}

@media (max-width: 768px) {
  .ai-page { grid-template-columns: 1fr; }
  .conv-side { display: none; }
}
</style>
