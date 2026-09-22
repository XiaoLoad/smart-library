const request = require('../../utils/request')
const sse = require('../../utils/sse')
const markdown = require('../../utils/markdown')
const auth = require('../../utils/auth')

const PAGE_SIZE = 20

Page({
  data: {
    conversations: [],
    currentConv: null,
    messages: [], // {id, role, content, html, thinking, thinkingOpen, toolEvents, pending}
    input: '',
    streaming: false,
    drawerOpen: false,
    hasMoreHistory: false,
    loadingHistory: false,
    scrollInto: '',
    scrollTick: 0,
  },

  onLoad() {
    this.loadConversations(true)
  },
  // 作为 tabBar 页常驻：每次进入补齐会话（登录后首切场景）
  onShow() {
    if (!this.data.currentConv) this.loadConversations(true)
  },
  onUnload() {
    if (this.streamTask && this.streamTask.abort) this.streamTask.abort()
    clearTimeout(this._renderTimer)
  },

  // ===== 会话 =====
  async loadConversations(autoSelect) {
    try {
      const res = await request.get('/ai/conversation/list', undefined, { silent: true })
      const conversations = (res.list || []).map((c) => ({
        ...c,
        titleText: c.title || '新会话',
      }))
      this.setData({ conversations })
      if (autoSelect) {
        if (conversations.length) this.selectConv(conversations[0].id)
        else this.createConversation()
      }
    } catch (e) {
      /* toast 已处理 */
    }
  },

  async createConversation() {
    try {
      const res = await request.post('/ai/conversation/create', {})
      await this.loadConversations(false)
      const list = this.data.conversations
      const target = (res && res.id) || (list[0] && list[0].id)
      this.setData({ drawerOpen: false })
      if (target) this.selectConv(target)
    } catch (e) {
      /* toast 已处理 */
    }
  },

  selectConv(id) {
    const conv = this.data.conversations.find((c) => c.id === id)
    if (!conv) return
    this.setData({ currentConv: conv, messages: [], drawerOpen: false, hasMoreHistory: false })
    this.loadHistory(true)
  },
  onSelectConv(e) {
    this.selectConv(Number(e.currentTarget.dataset.id))
  },

  onDeleteConv(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.showModal({
      title: '删除会话',
      content: '确认删除该会话及其聊天记录？',
      confirmColor: '#d93025',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await request.del('/ai/conversation/' + id)
          const remain = this.data.conversations.filter((c) => c.id !== id)
          this.setData({ conversations: remain })
          if (this.data.currentConv && this.data.currentConv.id === id) {
            if (remain.length) this.selectConv(remain[0].id)
            else this.createConversation()
          }
        } catch (err) {
          /* toast 已处理 */
        }
      },
    })
  },

  toggleDrawer() {
    this.setData({ drawerOpen: !this.data.drawerOpen })
  },

  // ===== 历史消息（游标向上翻页） =====
  async loadHistory(reset) {
    const conv = this.data.currentConv
    if (!conv || this.data.loadingHistory) return
    const oldest = this.data.messages[0]
    const beforeId = reset ? undefined : oldest && typeof oldest.id === 'number' ? oldest.id : undefined
    this.setData({ loadingHistory: true })
    try {
      const res = await request.get('/ai/message/list', {
        conversationId: conv.id,
        beforeId,
        pageSize: PAGE_SIZE,
      })
      const items = (res.list || []).map((m) => ({
        ...m,
        html: m.role === 'assistant' ? markdown.toHtml(m.content) : '',
        toolEvents: [],
        thinking: '',
      }))
      const messages = beforeId ? items.concat(this.data.messages) : items
      this.setData({
        messages,
        hasMoreHistory: items.length >= PAGE_SIZE,
      })
      if (reset) this.scrollToBottom()
    } catch (e) {
      /* toast 已处理 */
    } finally {
      this.setData({ loadingHistory: false })
    }
  },
  loadEarlier() {
    this.loadHistory(false)
  },

  // ===== 发送 & 流式渲染 =====
  onInput(e) {
    this.setData({ input: e.detail.value })
  },

  async send() {
    const content = this.data.input.trim()
    if (!content || this.data.streaming) return
    let conv = this.data.currentConv
    if (!conv) {
      await this.createConversation()
      conv = this.data.currentConv
      if (!conv) return
    }

    const userMsg = { id: 'u' + Date.now(), role: 'user', content }
    this._streamMsg = {
      id: 'a' + Date.now(),
      role: 'assistant',
      content: '',
      html: '',
      thinking: '',
      thinkingOpen: false,
      toolEvents: [],
      pending: true,
    }
    this._streamIdx = this.data.messages.length + 1
    this._streamFailed = false
    const tick = (this.data.scrollTick || 0) + 1
    this.setData({
      messages: this.data.messages.concat([userMsg, this._streamMsg]),
      input: '',
      streaming: true,
      scrollTick: tick,
      scrollInto: 'msg-bottom-' + tick,
    })

    this.streamTask = sse.streamPost({
      url: '/ai/chat',
      data: { conversationId: conv.id, content },
      onEvent: (evt) => this.onStreamEvent(evt),
      onDone: () => this.finishStream(),
      onError: (err) => this.failStream(err),
      onNonSse: (raw) => this.handleNonSse(raw),
      onFallback: () =>
        wx.showToast({ title: '当前微信版本过低，AI 回复可能不完整', icon: 'none' }),
    })
  },

  onStreamEvent({ event, data }) {
    const msg = this._streamMsg
    if (!msg) return
    if (event === 'thinking') {
      if (!data) return
      msg.thinking += data
    } else if (event === 'delta') {
      if (!data) return
      msg.pending = false
      msg.content += data
    } else if (event === 'reset') {
      // 工具开始执行：清空此前输出的过渡语，正文只保留最终回答（工具过程由卡片承载）
      msg.content = ''
      msg.pending = false
      this.flushStreamMsg()
      return
    } else if (event === 'final') {
      // 后端消毒后的完整正文，整体替换前端已拼接的内容
      msg.content = data || ''
      msg.pending = false
    } else if (event === 'tool') {
      if (!data) return
      this.mergeToolEvent(msg, data)
    } else if (event === 'done') {
      this.flushStreamMsg()
      return
    } else if (event === 'error') {
      this.failStream(new Error(data || 'AI 回复失败'))
      return
    } else {
      return
    }
    this.scheduleFlush()
  },

  // 后端没按 SSE 回帧时（最常见是 token 失效返回 JSON Result），必须给出可读出口
  handleNonSse(raw) {
    let body = null
    try {
      body = JSON.parse(raw)
    } catch (e) {
      /* 不是完整 JSON */
    }
    if (body && body.code === 401) {
      this.failStream(new Error('登录已过期，请重新登录'))
      auth.clear()
      setTimeout(() => wx.reLaunch({ url: '/pages/auth/login' }), 800)
      return
    }
    this.failStream(new Error((body && body.message) || 'AI 服务无响应，请稍后重试'))
  },

  // 工具调用卡片：start 新增，end/error 更新同名 start 卡片（对齐 Web 端逻辑）
  mergeToolEvent(msg, raw) {
    try {
      const info = JSON.parse(raw)
      const events = msg.toolEvents
      if (info.phase === 'start') {
        events.push({ name: info.name || '工具', detail: info.detail || '', phase: 'start' })
      } else {
        let target = null
        for (let i = events.length - 1; i >= 0; i--) {
          if (events[i].name === (info.name || '') && events[i].phase === 'start') {
            target = events[i]
            break
          }
        }
        if (target) {
          target.phase = info.phase
          if (info.detail) target.detail = target.detail ? target.detail + ' · ' + info.detail : info.detail
        } else {
          events.push({ name: info.name || '工具', detail: info.detail || '', phase: info.phase })
        }
      }
    } catch (e) {
      /* 忽略非法帧 */
    }
  },

  scheduleFlush() {
    if (this._renderTimer) return
    this._renderTimer = setTimeout(() => {
      this._renderTimer = null
      this.flushStreamMsg()
    }, 300)
  },

  flushStreamMsg() {
    const msg = this._streamMsg
    if (!msg) return
    this.setData({
      ['messages[' + this._streamIdx + '].content']: msg.content,
      ['messages[' + this._streamIdx + '].html']: msg.content ? markdown.toHtml(msg.content) : '',
      ['messages[' + this._streamIdx + '].thinking']: msg.thinking,
      ['messages[' + this._streamIdx + '].toolEvents']: msg.toolEvents,
      ['messages[' + this._streamIdx + '].pending']: msg.pending,
    })
    this.scrollToBottom()
  },

  finishStream() {
    clearTimeout(this._renderTimer)
    this._renderTimer = null
    const failed = this._streamFailed
    this.flushStreamMsg()
    this.setData({ streaming: false })
    this._streamMsg = null
    this.streamTask = null
    this._streamFailed = false
    // 回源拉最新消息，同步服务端持久化的 id 与消毒后的正文；
    // 失败/手动停止时不回源，否则会把前端已渲染的内容与提示一起冲掉
    if (!failed) setTimeout(() => this.loadHistory(true), 200)
  },

  failStream(err) {
    if (!this._streamMsg) return
    clearTimeout(this._renderTimer)
    this._renderTimer = null
    this.flushStreamMsg()
    this._streamFailed = true
    this.setData({
      streaming: false,
      ['messages[' + this._streamIdx + '].pending']: false,
    })
    this._streamMsg = null
    this.streamTask = null
    wx.showToast({ title: (err && err.message) || 'AI 回复失败', icon: 'none' })
  },

  stop() {
    if (this.streamTask && this.streamTask.abort) this.streamTask.abort()
    // 主动停止按失败收尾：不回源，保留用户已经看到的内容
    this._streamFailed = true
    this.finishStream()
  },

  toggleThinking(e) {
    const idx = this.findMsgIndex(e.currentTarget.dataset.id)
    if (idx < 0) return
    const key = 'messages[' + idx + '].thinkingOpen'
    const cur = this.data.messages[idx].thinkingOpen
    this.setData({ [key]: !cur })
  },

  findMsgIndex(id) {
    return this.data.messages.findIndex((m) => m.id === id)
  },

  // scroll-into-view 的值不变就不会重新触发滚动，用自增序号拼唯一 id
  scrollToBottom() {
    const tick = (this.data.scrollTick || 0) + 1
    this.setData({ scrollTick: tick, scrollInto: 'msg-bottom-' + tick })
  },
})
