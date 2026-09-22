const request = require('../../utils/request')
const { fmtDateTime } = require('../../utils/datetime')

const PAGE_SIZE = 10
const SEGMENTS = [
  { value: -1, label: '全部' },
  { value: 0, label: '待取书' },
  { value: 1, label: '借阅中' },
  { value: 5, label: '待确认' },
  { value: 3, label: '逾期' },
  { value: 2, label: '已归还' },
]

Page({
  data: {
    segments: SEGMENTS,
    current: -1,
    quota: null,
    list: [],
    page: 1,
    loading: false,
    finished: false,
    firstLoad: true,
  },

  onLoad() {
    this.reload()
  },
  onShow() {
    this.loadQuota()
    // 从详情页/取书码页回来时静默刷新首页，否则刚提交的借阅与归还看不到
    if (!this.data.firstLoad) this.reload(true)
  },
  onPullDownRefresh() {
    Promise.all([this.reload(), this.loadQuota()]).finally(() => wx.stopPullDownRefresh())
  },
  onReachBottom() {
    this.loadMore()
  },

  async loadQuota() {
    try {
      const quota = await request.get('/borrow/quota', undefined, { silent: true })
      this.setData({ quota })
    } catch (e) {
      /* 静默 */
    }
  },

  onSegment(e) {
    this.setData({ current: Number(e.currentTarget.dataset.value) })
    this.reload()
  },

  // silent 时不接管 loading、也不清空列表，用于 onShow 的就地刷新
  reload(silent) {
    if (silent) {
      this.setData({ page: 1, finished: false })
    } else {
      this.setData({ page: 1, finished: false, list: [] })
    }
    return this.load(silent)
  },
  loadMore() {
    if (this.data.finished || this.data.loading) return
    this.setData({ page: this.data.page + 1 })
    this.load()
  },
  async load(silent) {
    const { current, page } = this.data
    if (!silent) this.setData({ loading: true })
    try {
      const res = await request.get('/borrow/list', {
        page,
        size: PAGE_SIZE,
        status: current === -1 ? undefined : current,
      })
      const items = (res.list || []).map((b) => ({
        ...b,
        applyText: fmtDateTime(b.applyTime),
        borrowText: fmtDateTime(b.borrowTime),
        dueText: fmtDateTime(b.dueTime),
        pickupText: fmtDateTime(b.pickupDeadline),
        returnText: fmtDateTime(b.returnTime),
      }))
      const list = page === 1 ? items : this.data.list.concat(items)
      this.setData({
        list,
        firstLoad: false,
        finished: list.length >= (res.total || 0),
      })
    } catch (e) {
      this.setData({ firstLoad: false })
    } finally {
      if (!silent) this.setData({ loading: false })
    }
  },

  // 按借阅号取记录：分页追加或切换分段后数组下标会变，不能拿 index 当标识
  findRecord(e) {
    const no = e.currentTarget.dataset.borrowNo
    return this.data.list.find((r) => r.borrowNo === no) || null
  },

  goPickupCode(e) {
    const rec = this.findRecord(e)
    if (!rec) return
    wx.navigateTo({
      url: '/pages/borrow/pickup-code?borrowNo=' + rec.borrowNo,
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
          this.reload()
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
          this.reload()
        } catch (e) {
          /* toast 已处理 */
        }
      },
    })
  },

  goFine() {
    wx.navigateTo({ url: '/pages/fine/index' })
  },
})
