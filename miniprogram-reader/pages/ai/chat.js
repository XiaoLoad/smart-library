const request = require('../../utils/request')
const sse = require('../../utils/sse')
const markdown = require('../../utils/markdown')
const auth = require('../../utils/auth')
const badge = require('../../utils/badge')

const PAGE_SIZE = 20

// 工具名称中文映射（与 Web 端 AI 对话对齐）
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

// 美化工具返回的 JSON 快照（截断末尾的「…」需先去除再解析）
const formatJson = (dataJson) => {
  if (!dataJson) return ''
  try {
    const trimmed = String(dataJson).replace(/…$/, '')
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch (e) {
    return dataJson
  }
}

Page({
  data: {
    conversations: [],
    currentConv: null,
    messages: [], // {id, role, content, shown, html, segments, pending, thinking, thinkingOpen, toolEvents}
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
    if (this._revealTimer) clearTimeout(this._revealTimer)
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
        shown: '',
        pending: false,
        html: m.role === 'assistant' ? markdown.toHtml(m.content) : '',
        segments: m.role === 'assistant' ? markdown.toSegments(m.content) : [],
        thinking: '',
        thinkingOpen: false,
        toolEvents: [],
      }))
      // 后端 ORDER BY id DESC（最新在上），显示需转成正序（最旧在上、最新在下），与 Web 端 chat.vue 对齐
      const ordered = items.reverse()
      const messages = beforeId ? ordered.concat(this.data.messages) : ordered
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
      shown: '',
      html: '',
      segments: [],
      thinking: '',
      thinkingOpen: false,
      toolEvents: [],
      pending: true,
      _done: false,
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
      msg.content += data
      this.ensureReveal()
      this.scheduleFlush()
    } else if (event === 'reset') {
      // 工具开始执行：清空此前输出的过渡语，正文只保留最终回答（工具过程由卡片承载）
      msg.content = ''
      msg.shown = ''
      this.setData({ ['messages[' + this._streamIdx + '].shown']: '' })
      this.scheduleFlush()
      return
    } else if (event === 'final') {
      // 后端消毒后的完整正文，整体替换前端已拼接的内容
      if (data) msg.content = data
      this.ensureReveal()
      this.scheduleFlush()
    } else if (event === 'tool') {
      if (!data) return
      this.mergeToolEvent(msg, data)
      this.scheduleFlush()
    } else if (event === 'done') {
      this.finishStream()
      return
    } else if (event === 'error') {
      this.failStream(new Error(data || 'AI 回复失败'))
      return
    } else {
      return
    }
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
      badge.dismiss()
      setTimeout(() => wx.reLaunch({ url: '/pages/auth/login' }), 800)
      return
    }
    this.failStream(new Error((body && body.message) || 'AI 服务无响应，请稍后重试'))
  },

  // 工具调用卡片：start 新增，end/error 更新同名 start 卡片（对齐 Web 端逻辑）；
  // end 阶段携带 data 快照，可展开查看
  mergeToolEvent(msg, raw) {
    try {
      const info = JSON.parse(raw)
      const events = msg.toolEvents
      const name = info.name || ''
      if (info.phase === 'start') {
        events.push({
          name,
          label: toolLabel(name),
          phase: 'start',
          detail: info.detail || '',
          data: null,
          expanded: false,
        })
      } else {
        let target = null
        for (let i = events.length - 1; i >= 0; i--) {
          if (events[i].name === name && events[i].phase === 'start') {
            target = events[i]
            break
          }
        }
        const endDetail = info.detail || ''
        if (target) {
          target.phase = info.phase
          if (endDetail) {
            target.detail = target.detail ? target.detail + ' · ' + endDetail : endDetail
          }
          if (info.phase === 'end' && info.data) target.data = formatJson(info.data)
        } else {
          events.push({
            name,
            label: toolLabel(name),
            phase: info.phase,
            detail: endDetail,
            data: info.phase === 'end' ? formatJson(info.data || '') : null,
            expanded: false,
          })
        }
      }
    } catch (e) {
      /* 忽略非法帧 */
    }
  },

  toggleToolData(e) {
    const id = e.currentTarget.dataset.id
    const ti = Number(e.currentTarget.dataset.ti)
    const idx = this.findMsgIndex(id)
    if (idx < 0) return
    const key = 'messages[' + idx + '].toolEvents[' + ti + '].expanded'
    const cur = this.data.messages[idx].toolEvents[ti].expanded
    this.setData({ [key]: !cur })
  },

  // ===== 打字机：shown 是已揭示的文本，按 ~24ms/2 字从 content 增长 =====
  ensureReveal() {
    if (this._revealTimer) return
    this._revealTimer = setTimeout(() => this.revealTick(), 24)
  },
  revealTick() {
    const msg = this._streamMsg
    if (!msg) { this._revealTimer = null; return }
    if (msg.shown.length < msg.content.length) {
      const next = Math.min(msg.content.length, msg.shown.length + 2)
      msg.shown = msg.content.slice(0, next)
      this.setData({ ['messages[' + this._streamIdx + '].shown']: msg.shown })
      this._revealTimer = setTimeout(() => this.revealTick(), 24)
    } else {
      this._revealTimer = null
      if (msg._done) this.finalizeStream()
    }
  },
  finalizeStream() {
    const msg = this._streamMsg
    if (!msg) return
    this._revealTimer = null
    this.setData({
      ['messages[' + this._streamIdx + '].html']: msg.html,
      ['messages[' + this._streamIdx + '].segments']: msg.segments,
      ['messages[' + this._streamIdx + '].shown']: msg.content,
      ['messages[' + this._streamIdx + '].pending']: false,
    })
    this._streamMsg = null
    // 回源拉最新消息，同步服务端持久化的 id 与消毒后的正文；
    // 失败/手动停止时不回源，否则会把前端已渲染的内容与提示一起冲掉
    if (!this._streamFailed) setTimeout(() => this.loadHistory(true), 200)
  },

  scheduleFlush() {
    if (this._renderTimer) return
    this._renderTimer = setTimeout(() => {
      this._renderTimer = null
      this.flushStreamMsg()
    }, 120)
  },

  flushStreamMsg() {
    const msg = this._streamMsg
    if (!msg) return
    this.setData({
      ['messages[' + this._streamIdx + '].content']: msg.content,
      ['messages[' + this._streamIdx + '].thinking']: msg.thinking,
      ['messages[' + this._streamIdx + '].toolEvents']: msg.toolEvents,
      ['messages[' + this._streamIdx + '].pending']: msg.pending,
    })
    this.scrollToBottom()
  },

  finishStream() {
    clearTimeout(this._renderTimer)
    this._renderTimer = null
    const msg = this._streamMsg
    if (msg) {
      // 残留 start 工具卡 → end（停止旋转）
      msg.toolEvents.forEach((t) => { if (t.phase === 'start') t.phase = 'end' })
      msg.html = markdown.toHtml(msg.content)
      msg.segments = markdown.toSegments(msg.content)
      msg._done = true
    }
    this.setData({ streaming: false })
    const failed = this._streamFailed
    this._streamFailed = false
    if (!msg) return
    if (failed) {
      // 失败/停止：直接铺满，不等待打字机
      msg.shown = msg.content
      this.finalizeStream()
    } else if (msg.shown.length < msg.content.length || this._revealTimer) {
      this.ensureReveal()
    } else {
      this.finalizeStream()
    }
  },

  failStream(err) {
    const msg = this._streamMsg
    clearTimeout(this._renderTimer)
    this._renderTimer = null
    if (this._revealTimer) { clearTimeout(this._revealTimer); this._revealTimer = null }
    if (msg) {
      msg.toolEvents.forEach((t) => { if (t.phase === 'start') t.phase = 'end' })
      msg.shown = msg.content
      msg._done = true
      this.setData({
        streaming: false,
        ['messages[' + this._streamIdx + '].shown']: msg.content,
        ['messages[' + this._streamIdx + '].content']: msg.content,
        ['messages[' + this._streamIdx + '].pending']: false,
        ['messages[' + this._streamIdx + '].html']: '',
      })
      this._streamMsg = null
    } else {
      this.setData({ streaming: false })
    }
    this._streamFailed = false
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
