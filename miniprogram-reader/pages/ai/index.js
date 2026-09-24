const request = require('../../utils/core/request')
const sse = require('../../utils/core/sse')
const markdown = require('../../utils/feature/markdown')
const auth = require('../../utils/core/auth')
const badge = require('../../utils/feature/badge')

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

// 工具调用摘要（后端落库的 toolSummary JSON 数组）→ 徽章数据：同名工具合并计数
const parseToolChips = (summary) => {
  let names
  try {
    names = JSON.parse(summary || '[]')
  } catch (e) {
    return []
  }
  if (!Array.isArray(names)) return []
  const chips = []
  names.forEach((name) => {
    if (!name) return
    const found = chips.find((c) => c.name === name)
    if (found) found.count += 1
    else chips.push({ name, label: toolLabel(name), count: 1 })
  })
  return chips
}

// 本地时刻（刚发出的消息用发送时刻，不等回源才显示；历史消息用后端 createTime 切片）
const pad2 = (n) => (n < 10 ? '0' + n : '' + n)
const nowStamp = () => {
  const d = new Date()
  return {
    date: d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()),
    time: pad2(d.getHours()) + ':' + pad2(d.getMinutes()),
  }
}

// 轮次与日期装饰：一条用户消息为一轮（№01…），跨天插入日期戳；
// 同时产出目录定位条数据（每轮 {msgId, no, time, excerpt}）
const decorateMeta = (messages) => {
  const rounds = []
  let lastDate = ''
  messages.forEach((m) => {
    const raw = m.createTime || ''
    const date = m.dateText || (raw ? String(raw).slice(0, 10) : '')
    const time = m.timeText || (raw ? String(raw).slice(11, 16) : '')
    m.timeText = time
    m.dayLabel = date && date !== lastDate ? date : ''
    if (m.dayLabel) lastDate = date
    if (m.role === 'user') {
      m.roundNo = rounds.length + 1
      rounds.push({
        msgId: m.id,
        no: m.roundNo,
        time,
        excerpt: String(m.content || '').replace(/\s+/g, ' ').slice(0, 18) || '—',
      })
    } else {
      m.roundNo = 0
    }
  })
  return rounds
}

Page({
  data: {
    conversations: [],
    currentConv: null,
    messages: [], // {id, role, content, shown, html, segments, pending, thinking, thinkingOpen, toolEvents}
    input: '',
    kbOffset: 0, // 输入条上移补偿（px，见 onKeyboardHeightChange）
    streaming: false,
    drawerOpen: false,
    hasMoreHistory: false,
    loadingHistory: false,
    scrollInto: '',
    scrollTick: 0,
    navRounds: [], // 目录定位条：每轮用户消息 {msgId, no, time, excerpt}
    navCurrent: 0, // 当前轮索引（默认最后一轮）
    tocOpen: false, // 目录面板展开
    tocAnchor: '', // 面板打开后滚动锚点（渲染完成后再设，确保长列表定位生效）
  },

  onLoad() {
    this._anchorsDirty = true // 轮次锚点偏移需（重）测量，首个滚动事件前就绪
    this.loadConversations(true)
  },

  // 布局稳定后再量键盘补偿量（见 cacheKeyboardGap）
  onReady() {
    this.cacheKeyboardGap()
  },

  // 键盘补偿的几何量必须在「键盘收起时」量一次并缓存（onReady 即此时机）。
  // 原因：键盘弹出后 getWindowInfo 的 windowHeight 会随视口缩小（实测约缩小一个键盘高），
  // 若在键盘事件里现算，会把键盘高度也一并减掉，补偿归零、输入条纹丝不动。
  // 需要的量是「输入条底 → 屏幕底」的距离：补偿量 = 键盘高 − 该距离。
  // 这里直接量 DOM，而不是用 screenHeight − screenTop − windowHeight 反推：
  // 底部导航由悬浮改为贴地实栏后，输入条静止位置已高出屏幕底「栏高 + 安全区」，
  // 反推口径漏掉这层 padding，键盘一弹就把输入条多抬这一段，键盘上方漏出空隙。
  // rect.bottom 是相对页面显示区（导航栏下沿）的坐标，加 screenTop 即换成屏幕坐标。
  cacheKeyboardGap() {
    wx.createSelectorQuery()
      .select('.input-bar')
      .boundingClientRect((rect) => {
        if (!rect) return
        let info = {}
        try {
          info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {}
        } catch (e) {
          info = {}
        }
        const sh = info.screenHeight || 0
        const top = typeof info.screenTop === 'number' ? info.screenTop : info.statusBarHeight || 0
        const gap = sh - top - rect.bottom
        // 只信落在合理区间的值；异常（负值 / 过半屏）退化为 0，即补偿量 = 键盘高度
        this._bottomGap = gap > 0 && gap < sh / 2 ? gap : 0
      })
      .exec()
  },
  // 作为 tabBar 页常驻：每次进入补齐会话（登录后首切场景）
  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 })
    }
    if (!this.data.currentConv) this.loadConversations(true)
  },
  onUnload() {
    if (this.streamTask && this.streamTask.abort) this.streamTask.abort()
    clearTimeout(this._renderTimer)
    if (this._revealTimer) clearTimeout(this._revealTimer)
  },

  // ===== 会话 =====
  async fetchConversations() {
    try {
      const res = await request.get('/ai/conversation/list', undefined, { silent: true })
      return (res.list || []).map((c) => ({ ...c, titleText: c.title || '新会话' }))
    } catch (e) {
      return null
    }
  },

  async loadConversations(autoSelect) {
    const conversations = await this.fetchConversations()
    if (!conversations) return
    this.setData({ conversations })
    if (autoSelect) {
      if (conversations.length) this.selectConv(conversations[0].id)
      else this.createConversation()
    }
  },

  // 首条消息后服务端会按首条用户消息自动生成会话标题，回源同步（顶栏 + 抽屉列表）
  async syncConvTitle() {
    const conversations = await this.fetchConversations()
    if (!conversations) return
    const patch = { conversations }
    const cur = this.data.currentConv
    const found = cur && conversations.find((c) => c.id === cur.id)
    if (found && found.titleText !== cur.titleText) patch.currentConv = found
    this.setData(patch)
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
    this._ragCarry = null
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
        toolChips: parseToolChips(m.toolSummary),
        // 参考资料同理：rag 事件只在当轮推送，落库内容不含来源，历史消息无此块
        ragSources: [],
        ragOpen: -1,
        refTitle: '',
        refSnippet: '',
      }))
      // 后端 ORDER BY id DESC（最新在上），显示需转成正序（最旧在上、最新在下），与 Web 端 chat.vue 对齐
      const ordered = items.reverse()
      const messages = beforeId ? ordered.concat(this.data.messages) : ordered
      // 参考资料不落库：回源刷掉刚结束的一轮后就没了，这里把它补回最后一条 AI 消息
      const carry = this._ragCarry
      this._ragCarry = null
      if (reset && carry && carry.length) {
        for (let i = messages.length - 1; i >= 0; i--) {
          if (messages[i].role === 'assistant') {
            messages[i].ragSources = carry
            break
          }
        }
      }
      const rounds = decorateMeta(messages)
      const patch = {
        messages,
        hasMoreHistory: items.length >= PAGE_SIZE,
        navRounds: rounds,
      }
      if (reset) {
        patch.navCurrent = rounds.length - 1
      } else {
        // 向上翻页后轮次整体前移，按 msgId 找回原选中轮
        const keep = this.data.navRounds[this.data.navCurrent]
        const ki = keep ? rounds.findIndex((r) => r.msgId === keep.msgId) : -1
        patch.navCurrent = ki >= 0 ? ki : rounds.length - 1
      }
      this.setData(patch)
      this._anchorsDirty = true // 消息集变化，轮次锚点偏移失效
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

  // ===== 目录定位条：tick 点击直接跳；目录按钮展开面板（面板把对话推下去，不遮挡） =====
  onToggleToc() {
    const open = !this.data.tocOpen
    // 面板常驻渲染（靠 .open 类做展开动画），元素已在 DOM，锚点一次设置即可生效
    this.setData({
      tocOpen: open,
      tocAnchor: open ? 'tocrow-' + this.data.navCurrent : '',
    })
  },
  onNavJump(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const r = this.data.navRounds[idx]
    if (!r) return
    this.setData({ navCurrent: idx, tocOpen: false, scrollInto: 'msg-' + r.msgId })
  },
  onSuggest(e) {
    const q = e.currentTarget.dataset.q
    if (!q || this.data.streaming) return
    this.setData({ input: q })
    this.send()
  },

  // ===== 滚动跟随（scrollspy）：以「顶部已越过的最近一轮」为当前轮，驱动 tick 高亮 =====
  onMsgsScroll(e) {
    const st = e.detail.scrollTop || 0
    this._msgsTop = st
    if (this._anchorsDirty && !this._measuring) this.measureAnchors()
    const now = Date.now()
    if (now - (this._lastSpy || 0) < 80) return // 节流
    this._lastSpy = now
    const arr = this._anchors
    if (!arr || !arr.length) return
    const bias = 60 // 视口顶部下方 60px 内仍算上一轮
    let cur = 0
    for (let i = 0; i < arr.length; i++) {
      if (arr[i] <= st + bias) cur = i
      else break
    }
    if (cur !== this.data.navCurrent) this.setData({ navCurrent: cur })
  },

  // 量各轮眉标（.rnd）在滚动内容中的偏移；消息增删后由 _anchorsDirty 触发重测
  measureAnchors() {
    this._measuring = true
    wx.createSelectorQuery()
      .selectAll('.rnd')
      .boundingClientRect()
      .select('.msgs')
      .boundingClientRect()
      .exec((res) => {
        this._measuring = false
        this._anchorsDirty = false
        if (!res || !res[0] || !res[1]) return
        const base = res[1].top
        this._anchors = res[0].map((r) => r.top - base + (this._msgsTop || 0))
      })
  },

  // ===== 发送 & 流式渲染 =====
  onInput(e) {
    this.setData({ input: e.detail.value })
  },

  // 软键盘高度变化：输入条上移，恰好停在键盘上沿
  // 补偿量 = 键盘高 − 页面底到屏幕底的间距（后者在 onLoad 已缓存，见 cacheKeyboardGap）
  onKeyboardHeightChange(e) {
    const h = e.detail.height || 0
    const delta = h - (this._bottomGap || 0)
    // 键盘比「输入条到屏幕底」还矮时不遮挡，无需上移
    this.setData({ kbOffset: delta > 0 ? delta : 0 })
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

    const st = nowStamp()
    const userMsg = { id: 'u' + Date.now(), role: 'user', content, dateText: st.date, timeText: st.time }
    this._streamMsg = {
      id: 'a' + Date.now(),
      dateText: st.date,
      timeText: st.time,
      role: 'assistant',
      content: '',
      shown: '',
      html: '',
      segments: [],
      thinking: '',
      thinkingOpen: false,
      toolEvents: [],
      toolChips: [], // 流式期间保持空（卡片展示），finalize 后由 toolEvents 收拢为徽章
      // 知识库来源（SSE rag 事件）：在调用 LLM 前推送，故先出「参考资料」行、正文随后流式出现；
      // 后端不落库，历史消息无此块
      ragSources: [],
      ragOpen: -1, // 当前展开片段摘要的序号，-1 表示全部收起
      refTitle: '', // 展开中的来源标题与摘要（wxml 不做嵌套下标取值，展开时就近取好）
      refSnippet: '',
      pending: true,
      _done: false,
    }
    this._streamIdx = this.data.messages.length + 1
    this._streamFailed = false
    this._ragCarry = null // 上一轮的参考资料不再回贴
    const messages = this.data.messages.concat([userMsg, this._streamMsg])
    const rounds = decorateMeta(messages)
    const tick = (this.data.scrollTick || 0) + 1
    this.setData({
      messages,
      input: '',
      streaming: true,
      scrollTick: tick,
      scrollInto: 'msg-bottom-' + tick,
      navRounds: rounds,
      navCurrent: rounds.length - 1,
      tocOpen: false,
    })
    this._anchorsDirty = true // 新一轮消息入列，锚点偏移失效

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
    } else if (event === 'rag') {
      // 知识库来源：检索命中即「参考资料」行；正文(delta)随后才来
      if (!data) return
      this.mergeRagSources(msg, data)
      this.scrollToBottom()
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
      // 工具执行完成即累计徽章计数（finalize 时一次性渲染，流式期间不展示）
      if (info.phase === 'end') this.bumpToolChip(msg, name)
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

  // 知识库来源：同一轮可能多批推送（多轮工具调用会重复检索），按「标题 + 摘要」去重
  mergeRagSources(msg, raw) {
    let list
    try {
      list = JSON.parse(raw)
    } catch (e) {
      return
    }
    if (!Array.isArray(list)) return
    const sources = msg.ragSources || (msg.ragSources = [])
    list.forEach((item) => {
      const docTitle = (item && item.docTitle) || '未命名文档'
      const snippet = (item && item.snippet) || ''
      if (sources.some((s) => s.docTitle === docTitle && s.snippet === snippet)) return
      sources.push({ docTitle, snippet })
    })
    if (!sources.length) return
    // 只追加、不重排，故展开序号保持有效，这里不重置 ragOpen
    this.setData({ ['messages[' + this._streamIdx + '].ragSources']: sources })
  },

  // 点参考资料药丸：展开 / 收起该片段摘要（历史消息无来源，无药丸可点）
  onRefTap(e) {
    const idx = this.findMsgIndex(e.currentTarget.dataset.id)
    if (idx < 0) return
    const ri = Number(e.currentTarget.dataset.ri)
    const msg = this.data.messages[idx]
    const close = msg.ragOpen === ri
    const src = close ? null : (msg.ragSources || [])[ri]
    this.setData({
      ['messages[' + idx + '].ragOpen']: close ? -1 : ri,
      ['messages[' + idx + '].refTitle']: src ? src.docTitle : '',
      ['messages[' + idx + '].refSnippet']: src ? src.snippet : '',
    })
  },

  // 徽章计数：同名工具合并（仅内存累计，finalizeStream 才 setData 渲染）
  bumpToolChip(msg, name) {
    if (!name) return
    const chips = msg.toolChips || (msg.toolChips = [])
    const found = chips.find((c) => c.name === name)
    if (found) found.count += 1
    else chips.push({ name, label: toolLabel(name), count: 1 })
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

  // 点徽章：展开该工具最近一次返回的快照（本轮流式数据）；再点收起。
  // 历史消息的 toolEvents 已清空，徽章为纯标签，无快照可展开则静默返回
  onChipTap(e) {
    const id = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name
    const idx = this.findMsgIndex(id)
    if (idx < 0) return
    const msg = this.data.messages[idx]
    if (msg.snapTool === name) {
      this.setData({
        ['messages[' + idx + '].snapTool']: '',
        ['messages[' + idx + '].snapData']: '',
      })
      return
    }
    let data = ''
    const events = msg.toolEvents || []
    for (let i = events.length - 1; i >= 0; i--) {
      if (events[i].name === name && events[i].data) {
        data = events[i].data
        break
      }
    }
    if (!data) return
    this.setData({
      ['messages[' + idx + '].snapTool']: name,
      ['messages[' + idx + '].snapData']: data,
    })
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
      // 工具卡片收拢为徽章行（toolChips 一旦非空，wxml 即隐藏卡片改显徽章）
      ['messages[' + this._streamIdx + '].toolChips']: msg.toolChips || [],
    })
    this._streamMsg = null
    // 回源拉最新消息，同步服务端持久化的 id 与消毒后的正文；
    // 失败/手动停止时不回源，否则会把前端已渲染的内容与提示一起冲掉
    if (!this._streamFailed) {
      // 参考资料不落库，回源会把它冲掉，暂存给 loadHistory 贴回最后一条 AI 消息
      this._ragCarry = msg.ragSources && msg.ragSources.length ? msg.ragSources : null
      setTimeout(() => {
        this.loadHistory(true)
        this.syncConvTitle()
      }, 200)
    }
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
