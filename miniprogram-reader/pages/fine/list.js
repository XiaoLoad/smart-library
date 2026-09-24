const request = require('../../utils/core/request')
const { mockPay } = require('../../utils/feature/mock-pay')
const { fmtDateTime, fmtMoney } = require('../../utils/feature/datetime')

const TABS = [
  { value: -1, label: '全部' },
  { value: 0, label: '未缴' },
  { value: 1, label: '已缴' },
]

// 一个分段一屏；三屏共用一次 /fine/list 全量拉取，各自只放自己状态的那部分
function blankPane(value) {
  return { key: value, list: [] }
}

Page({
  data: {
    tabs: TABS,
    current: 0, // 分段下标，同时就是 swiper 的 current
    panes: TABS.map((t) => blankPane(t.value)),
    all: [],
    payingId: 0,
    loading: true,
    dockStuck: false, // 分段坞是否已吸附在顶部
    swiperH: 0, // 当前分段内容的实际高度；量到之前先用 wxss 里的兜底值
  },

  onLoad() {
    this.load()
  },
  onReady() {
    this.measureDock()
    this.fitPanes()
  },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
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
     撑满「坞以下的可视区」。内容一变就重测。 */
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
  // 本页不是 tab 页、没有自定义 tabBar 压底：列表活动区 = 视口 - 坞。窗口度量缓存一次。
  minPaneH() {
    if (this._winH === undefined) {
      try {
        const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {}
        this._winH = info.windowHeight || 0
      } catch (e) {
        this._winH = 0
      }
    }
    if (!this._winH || this._dockH === undefined) return 0
    return Math.max(0, Math.round(this._winH - this._dockH))
  },

  /* —— 分段（swiper） —— */
  onTab(e) {
    const i = TABS.findIndex((t) => t.value === Number(e.currentTarget.dataset.value))
    if (i < 0 || i === this.data.current) return
    // 先改 current，swiper 自己滑过去；随后 bindchange 回来时两者已相等，不会重复处理
    this.setData({ current: i }, () => this.fitPanes())
  },
  onSwiperChange(e) {
    const i = e.detail.current
    if (i === this.data.current) return
    this.setData({ current: i }, () => this.fitPanes())
  },

  /* —— 列表 —— */
  async load() {
    this.setData({ loading: true })
    try {
      const res = await request.get('/fine/list')
      const all = (res.list || []).map((f) => ({
        ...f,
        amountText: fmtMoney(f.amount),
        createText: fmtDateTime(f.createTime),
        paidText: fmtDateTime(f.paidTime),
      }))
      this.setData({ all }, () => this.distribute())
    } catch (e) {
      /* toast 已处理 */
    } finally {
      this.setData({ loading: false }, () => this.fitPanes())
    }
  },

  // 一份全量数据摊给三个分段屏，各屏只放自己状态的那部分
  distribute() {
    const { all } = this.data
    const d = {}
    TABS.forEach((t, i) => {
      d['panes[' + i + '].list'] = t.value === -1 ? all : all.filter((f) => f.status === t.value)
    })
    this.setData(d, () => this.fitPanes())
  },

  // 模拟缴费：确认弹窗 → 假装支付 → 调用后端缴费登记
  onPay(e) {
    const id = Number(e.currentTarget.dataset.id)
    const fine = this.data.all.find((f) => f.id === id)
    if (!fine) return
    mockPay({ amount: fine.amount, title: '《' + fine.bookTitle + '》逾期罚款' })
      .then(async () => {
        this.setData({ payingId: id })
        try {
          await request.post('/fine/pay', { id })
          wx.showToast({ title: '缴费登记成功', icon: 'success' })
          await this.load()
        } catch (err) {
          /* toast 已处理 */
        } finally {
          this.setData({ payingId: 0 })
        }
      })
      .catch(() => {})
  },
})