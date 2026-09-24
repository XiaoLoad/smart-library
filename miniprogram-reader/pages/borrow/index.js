const request = require('../../utils/core/request')
const cache = require('../../utils/core/cache')
const { fmtDateTime } = require('../../utils/feature/datetime')

const PAGE_SIZE = 10
// 自定义 tabBar 的高度，rpx；与 app.wxss 的 --tabbar-h 同值（tabBar 浮在页面之上，
// 算「列表活动区」高度时要把它和底部安全区让出去）
const TABBAR_RPX = 112
const SEGMENTS = [
  { value: -1, label: '全部' },
  { value: 0, label: '待取书' },
  { value: 1, label: '借阅中' },
  { value: 5, label: '待确认' },
  { value: 3, label: '逾期' },
  { value: 2, label: '已归还' },
]

// 一个分段一屏。六屏同时活在 swiper 里，共用一份 list 会把上一屏的记录带进新屏，
// 所以列表、分页、加载态各记一份。key 给 wx:key 用，取分段值保证唯一。
function blankPane(key) {
  return { key, list: [], page: 1, loading: false, finished: false, firstLoad: true }
}

function decorate(b) {
  return {
    ...b,
    applyText: fmtDateTime(b.applyTime),
    borrowText: fmtDateTime(b.borrowTime),
    dueText: fmtDateTime(b.dueTime),
    pickupText: fmtDateTime(b.pickupDeadline),
    returnText: fmtDateTime(b.returnTime),
  }
}

Page({
  data: {
    segments: SEGMENTS,
    current: 0, // 分段下标，同时就是 swiper 的 current
    panes: SEGMENTS.map((s) => blankPane(s.value)),
    quota: null,
    dockStuck: false, // 状态坞是否已吸附在顶部
    swiperH: 0, // 当前分段内容的实际高度；量到之前先用 wxss 里的兜底值
  },

  onLoad() {
    this.reload(0)
  },
  onReady() {
    this.measureDock()
    this.fitPanes()
  },
  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 })
    }
    this.loadQuota()
    // 从详情页/取书码页回来时静默刷新当前分段，否则刚提交的借阅与归还看不到
    const i = this.data.current
    if (!this.data.panes[i].firstLoad) this.reload(i, true)
  },
  // 下拉刷新与触底翻页都是页面级的：列表不自带滚动，纵向手势整条交给页面
  onPullDownRefresh() {
    Promise.all([this.reload(this.data.current, true), this.loadQuota(true)]).finally(() => wx.stopPullDownRefresh())
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
    const stuck = e.scrollTop >= (this._dockTop === undefined ? 200 : this._dockTop)
    if (stuck !== this.data.dockStuck) this.setData({ dockStuck: stuck })
  },

  /* swiper 必须定高，但高度不写死：量当前屏内容的真实高度设给它，内容多高 swiper
     就多高，超出屏幕的部分由页面往下滚。但有一个下限——空态/骨架那一屏内容很矮，
     若跟着缩，坞底下那一大片就不是 swiper 了，横滑切段只在空态那一小块里有效；
     所以高度至少撑满「坞以下的可视区」。内容一变（清空/铺缓存/落数据/切段）就重测。 */
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

  /* —— 分段（swiper） —— */
  onSegment(e) {
    const i = SEGMENTS.findIndex((s) => s.value === Number(e.currentTarget.dataset.value))
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
  // 进入某一屏：没加载过就拉第一页；加载过就就地静默刷新（切段总会重新拉一次）
  enter(i) {
    this.reload(i, !this.data.panes[i].firstLoad)
  },

  /* —— 列表 —— */
  // 缓存键跟着分段走：切到「逾期」看到的就是逾期那一屏的旧数据
  cacheKey(i) {
    return 'borrow:' + SEGMENTS[i].value
  },
  pane(i) {
    return this.data.panes[i]
  },
  // 只改某一屏的字段，六屏互不干扰
  setPane(i, patch, cb) {
    const d = {}
    Object.keys(patch).forEach((k) => {
      d['panes[' + i + '].' + k] = patch[k]
    })
    this.setData(d, cb)
  },

  async loadQuota(fresh) {
    if (!fresh) {
      const stale = cache.read('borrow:quota')
      if (stale) this.setData({ quota: stale }, () => this.measureDock())
    }
    try {
      const quota = await request.get('/borrow/quota', undefined, { silent: true })
      // 额度卡在坞的上方，它到货会把坞往下推——落屏后重新量一次吸附阈值
      this.setData({ quota }, () => this.measureDock())
      cache.write('borrow:quota', quota)
    } catch (e) {
      /* 静默 */
    }
  },

  // silent：不接管 loading、也不清空列表，用于 onShow、切段后的就地刷新与下拉刷新
  reload(i, silent) {
    this.setPane(i, { page: 1, finished: false, list: silent ? this.pane(i).list : [] }, () => this.fitPanes())
    return this.load(i, silent)
  },
  loadMore(i) {
    const p = this.pane(i)
    if (p.finished || p.loading) return
    this.setPane(i, { page: p.page + 1 })
    this.load(i)
  },
  async load(i, silent) {
    const { page } = this.pane(i)
    const status = SEGMENTS[i].value
    // silent：屏上本来就有数据，不必再用缓存铺一遍
    if (page === 1 && !silent) {
      const stale = cache.read(this.cacheKey(i))
      if (stale) this.setPane(i, { list: stale.items, firstLoad: false, finished: stale.items.length >= stale.total }, () => this.fitPanes())
    }
    if (!silent) this.setPane(i, { loading: true })
    try {
      const res = await request.get('/borrow/list', {
        page,
        size: PAGE_SIZE,
        status: status === -1 ? undefined : status,
      })
      const items = (res.list || []).map(decorate)
      const total = res.total || 0
      const list = page === 1 ? items : this.pane(i).list.concat(items)
      if (page === 1) cache.write(this.cacheKey(i), { items, total })
      this.setPane(i, { list, firstLoad: false, loading: false, finished: list.length >= total }, () => this.fitPanes())
    } catch (e) {
      this.setPane(i, { firstLoad: false, loading: false })
    }
  },

  // 按借阅号取记录：分页追加或切换分段后数组下标会变，不能拿 index 当标识
  findRecord(e) {
    const no = e.currentTarget.dataset.borrowNo
    return this.pane(this.data.current).list.find((r) => r.borrowNo === no) || null
  },

  goPickupCode(e) {
    const rec = this.findRecord(e)
    if (!rec) return
    wx.navigateTo({
      url: '/packageExtra/pages/pickup-code?borrowNo=' + rec.borrowNo,
      success(res) {
        res.eventChannel.emit('record', rec)
      },
    })
  },

  onRenew(e) {
    const rec = this.findRecord(e)
    if (!rec) return
    wx.showModal({
      title: '续借',
      content: '确认续借《' + rec.bookTitle + '》？',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await request.post('/borrow/renew', { id: rec.id, borrowNo: rec.borrowNo })
          wx.showToast({ title: '续借成功', icon: 'success' })
          this.reload(this.data.current)
          this.loadQuota()
        } catch (e) {
          /* 续借次数用尽等 toast 已处理 */
        }
      },
    })
  },

  onReturn(e) {
    const rec = this.findRecord(e)
    if (!rec) return
    wx.showModal({
      title: '申请归还',
      content: '确认申请归还《' + rec.bookTitle + '》？提交后请尽快到馆还书。',
      confirmText: '申请归还',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await request.post('/borrow/return', { id: rec.id, borrowNo: rec.borrowNo })
          wx.showToast({ title: '已提交归还申请', icon: 'success' })
          this.reload(this.data.current)
        } catch (e) {
          /* toast 已处理 */
        }
      },
    })
  },

  goFine() {
    wx.navigateTo({ url: '/pages/fine/list' })
  },
})