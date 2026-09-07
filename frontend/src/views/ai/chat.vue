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
import { ref, nextTick, onMounted } from 'vue'
import { Plus, ChatDotRound, MagicStick, Promotion } from '@element-plus/icons-vue'
import { aiApi } from '@/api'

const conversations = ref([])
const currentConvId = ref(null)
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgListRef = ref(null)

const quickPrompts = [
  '我当前借了哪些书？',
  '查找哲学类图书',
  '本月借阅最多的书是什么？',
  '我有未缴罚款吗？',
]

// 模拟 AI 回复
const aiReplies = [
  '您好，我已为您查询。当前馆藏图书 12,834 册，可借 8,247 册，借阅中 389 本，逾期 23 本。',
  '根据您的借阅记录，您当前借阅中图书 2 本：\n1. 《红楼梦》— 应还日期 2026-10-01\n2. 《三体》— 应还日期 2026-09-19（已逾期 18 天）\n\n建议您尽快归还《三体》以避免产生更多罚款。',
  '本月借阅最多的图书是《百年孤独》，共借出 86 次。其次是《红楼梦》（72 次）和《三体》（65 次）。',
  '您当前有 1 笔未缴罚款，金额 ¥15.00，关联借阅《百年孤独》（超期 30 天）。请尽快到馆缴费或在线支付。',
  '哲学类图书位于 C 区 3 排，馆藏 5 种推荐：《存在与时间》《理想国》《中国哲学简史》《查拉图斯特拉如是说》《纯粹理性批判》。',
]

const loadConversations = async () => {
  const data = await aiApi.conversationList()
  conversations.value = data.list
  if (data.list.length) {
    await switchConv(data.list[0].id)
  }
}

const switchConv = async (id) => {
  currentConvId.value = id
  const data = await aiApi.messageList(id)
  messages.value = data.list
  await scrollToBottom()
}

const newConv = () => {
  const newId = Date.now()
  conversations.value.unshift({
    id: newId,
    title: '新的对话',
    model: 'qwen-plus',
    status: 1,
    createTime: new Date().toISOString().replace('T', ' ').substring(0, 19),
  })
  currentConvId.value = newId
  messages.value = []
}

const sendPrompt = (prompt) => {
  input.value = prompt
  send()
}

const send = async () => {
  if (!input.value.trim() || thinking.value) return
  const content = input.value.trim()
  input.value = ''
  messages.value.push({
    id: Date.now(),
    conversationId: currentConvId.value,
    role: 'user',
    content,
    createTime: new Date().toISOString(),
  })
  await scrollToBottom()

  thinking.value = true
  // 模拟流式延迟
  await new Promise((r) => setTimeout(r, 800 + Math.random() * 600))
  const reply = aiReplies[Math.floor(Math.random() * aiReplies.length)] || aiReplies[0]
  messages.value.push({
    id: Date.now() + 1,
    conversationId: currentConvId.value,
    role: 'assistant',
    content: reply,
    createTime: new Date().toISOString(),
  })
  thinking.value = false
  await scrollToBottom()
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

.conv-meta { flex: 1; min-width: 0; }
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
