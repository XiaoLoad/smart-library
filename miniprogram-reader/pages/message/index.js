const request = require('../../utils/core/request')
const badge = require('../../utils/feature/badge')
const { fmtDateTime } = require('../../utils/feature/datetime')

const PAGE_SIZE = 10
// 自定义 tabBar 的高度，rpx；与 app.wxss 的 --tabbar-h 同值（tabBar 浮在页面之上，
// 算「列表活动区」高度时要把它和底部安全区让出去）
const TABBAR_RPX = 112
const TYPE_TEXT = { 1: '系统', 2: '超期', 3: '罚款', 4: '到期' }

// 一个分段一屏：通知/公告各自一份列表与加载态
const SEGMENTS = [
  { key: 'notice', label: '通知' },
  { key: 'announce', label: '公告' },
]

function blankPane(key) {
  return { key, list: [], page: 1, loading: false, finished: false, firstLoad: true }
}

Page({
  data: {
    segments: SEGMENTS,
    current: 0, // 分段下标，同时就是 swiper 的 current
    panes: SEGMENTS.map((s) => blankPane(s.key)),
    unread: 0,
    dockStuck: false, // 分段坞是否已吸附在顶部
    swiperH: 0, // 当前分段内容的实际高度；量到之前先用 wxss 里的兜底值
  },

  onLoad() {
    this.enter(0)
  },
  onReady() {
    this.measureDock()
    this.fitPanes()
  },
  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 })
    }
    this.refreshUnread()
  },
  onPullDownRefresh() {
    Promise.all([this.reload(this.data.current, true), this.refreshUnread()]).finally(() => wx.stopPullDownRefresh())
  },
  onReachBottom() {
    this.loadMore(this.data.current)
  },

  /* —— 尺寸 ——
     坞的吸附阈值与「列表最小高度」都从坞上量：阈值是坞的页面偏移量（onPageScroll
     逐帧只做比较），坞高用来算视口里列表活动区还剩多高 */
  measureDock() {
    wx.createSelectorQuery()
      .select('.dock')
      .boundingClientRect()
      .selectViewport()
      .scrollOffset()
      .exec((res) => {
        const rect = res && res[0]
        const vp = res && res[1]
        if (!rect) return
        this._dockTop = rect.top + ((vp && vp.scrollTop) || 0)
        this._dockH = rect.height
        this.fitPanes()
      })
  },
  onPageScroll(e) {
    const stuck = e.scrollTop >= (this._dockTop === undefined ? 120 : this._dockTop)
    if (stuck !== this.data.dockStuck) this.setData({ dockStuck: stuck })
  },

  /* swiper 必须定高，但高度不写死：量当前屏内容的真实高度设给它，内容多高 swiper
     就多高，超出屏幕的部分由页面往下滚。但有一个下限——空态那一屏内容很矮，若跟着缩，
     坞底下那一大片就不是 swiper 了，横滑切段只在空态那一小块里有效；所以高度至少
     撑满「坞以下的可视区」。内容一变（清空/落数据/切段）就重测。 */
  fitPanes() {
    wx.createSelectorQuery()
      .selectAll('.pane')
      .boundingClientRect((rects) => {
        const r = rects && rects[this.data.current]
        if (!r) return
        let h = Math.ceil(r.height)
        const min = this.minPaneH()
        if (min && h < min) h = min
        if (h > 0 && h !== this.data.swiperH) this.setData({ swiperH: h })
      })
      .exec()
  },
  // 坞吸顶时列表活动区的高度 = 视口 - 坞 - tabBar - 底部安全区。窗口度量缓存一次。
  minPaneH() {
    if (this._winH === undefined) {
      try {
        const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {}
        this._winH = info.windowHeight || 0
        this._minBase = (TABBAR_RPX * (info.windowWidth || 375)) / 750 +
          (info.safeArea ? Math.max(0, info.screenHeight - info.safeArea.bottom) : 0)
      } catch (e) {
        this._winH = 0
        this._minBase = 0
      }
    }
    if (!this._winH || this._dockH === undefined) return 0
    return Math.max(0, Math.round(this._winH - this._dockH - this._minBase))
  },

  /* 分段（swiper） */
  onSegment(e) {
    const i = SEGMENTS.findIndex((s) => s.key === e.currentTarget.dataset.key)
    if (i < 0 || i === this.data.current) return
    // 先改 current，swiper 自己滑过去；随后 bindchange 回来时两者已相等，不会重复加载
    this.setData({ current: i }, () => this.fitPanes())
    this.enter(i)
  },
  onSwiperChange(e) {
    const i = e.detail.current
    if (i === this.data.current) return
    this.setData({ current: i }, () => this.fitPanes())
    this.enter(i)
  },
  // 进入某一屏：没加载过就拉数据；加载过就就地静默刷新
  enter(i) {
    this.reload(i, !this.pane(i).firstLoad)
  },

  /* 列表 */
  pane(i) {
    return this.data.panes[i]
  },
  // 只改某一屏的字段，两屏互不干扰
  setPane(i, patch, cb) {
    const d = {}
    Object.keys(patch).forEach((k) => {
      d['panes[' + i + '].' + k] = patch[k]
    })
    this.setData(d, cb)
  },

  reload(i, silent) {
    this.setPane(i, { page: 1, finished: false, list: silent ? this.pane(i).list : [] }, () => this.fitPanes())
    return SEGMENTS[i].key === 'notice' ? this.loadNotices(i) : this.loadAnnounces(i, silent)
  },
  // 公告分页；通知是一次全量（finished 恒为 true），翻到这里自然是空操作
  loadMore(i) {
    if (SEGMENTS[i].key !== 'announce') return
    const p = this.pane(i)
    if (p.finished || p.loading) return
    this.setPane(i, { page: p.page + 1 })
    this.loadAnnounces(i)
  },

  // 通知
  async loadNotices(i) {
    this.setPane(i, { loading: true })
    try {
      const res = await request.get('/notification/list')
      const list = (res.list || []).map((n) => ({
        ...n,
        typeText: TYPE_TEXT[n.type] || '消息',
        timeText: fmtDateTime(n.createTime),
      }))
      this.setPane(i, { list, firstLoad: false, loading: false, finished: true }, () => this.fitPanes())
      this.refreshUnread()
    } catch (e) {
      this.setPane(i, { firstLoad: false, loading: false })
    }
  },

  // 未读角标由 utils/badge 统一维护，这里只把数量同步给页头
  refreshUnread() {
    return badge.refresh().then((n) => {
      this.setData({ unread: n })
      return n
    })
  },

  async onNoticeTap(e) {
    const idx = e.currentTarget.dataset.index
    const n = this.pane(0).list[idx]
    if (!n) return
    if (!n.isRead) {
      try {
        await request.post('/notification/read', { id: n.id }, { silent: true })
        this.setData({ ['panes[0].list[' + idx + '].isRead']: 1 })
        this.refreshUnread()
      } catch (err) {
        /* 静默 */
      }
    }
  },

  onReadAll() {
    wx.showModal({
      title: '全部已读',
      content: '确认将所有通知标记为已读？',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await request.post('/notification/read-all')
          this.reload(0)
        } catch (e) {
          /* toast 已处理 */
        }
      },
    })
  },

  // 公告
  async loadAnnounces(i, silent) {
    const { page } = this.pane(i)
    this.setPane(i, { loading: true })
    try {
      const res = await request.get('/announcement/list', { page, size: PAGE_SIZE })
      const items = (res.list || []).map((a) => ({ ...a, timeText: fmtDateTime(a.publishTime) }))
      const list = page === 1 ? items : this.pane(i).list.concat(items)
      this.setPane(i, { list, firstLoad: false, loading: false, finished: list.length >= (res.total || 0) }, () => this.fitPanes())
    } catch (e) {
      this.setPane(i, { firstLoad: false, loading: false })
    }
  },
  goAnnounce(e) {
    wx.navigateTo({ url: '/pages/message/notice-detail?id=' + e.currentTarget.dataset.id })
  },
})