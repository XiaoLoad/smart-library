const request = require('../../utils/request')
const { fmtDateTime } = require('../../utils/datetime')

const PAGE_SIZE = 10
const TYPE_TEXT = { 1: '系统', 2: '超期', 3: '罚款', 4: '到期' }

Page({
  data: {
    tab: 'notice', // notice | announce
    notices: [],
    unread: 0,
    items: [],
    page: 1,
    loading: false,
    finished: false,
    firstLoad: true,
  },

  onLoad() {
    this.reload()
  },
  onShow() {
    this.refreshUnread()
  },
  onPullDownRefresh() {
    this.reload().finally(() => wx.stopPullDownRefresh())
  },
  onReachBottom() {
    if (this.data.tab === 'announce') this.loadMore()
  },

  onTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
    this.reload()
  },

  reload() {
    this.setData({ page: 1, finished: false, items: [], notices: [] })
    return this.data.tab === 'notice' ? this.loadNotices() : this.loadAnnounces()
  },

  // ===== 通知 =====
  async loadNotices() {
    this.setData({ loading: true })
    try {
      const res = await request.get('/notification/list')
      const notices = (res.list || []).map((n) => ({
        ...n,
        typeText: TYPE_TEXT[n.type] || '消息',
        timeText: fmtDateTime(n.createTime),
      }))
      this.setData({ notices, firstLoad: false })
      this.refreshUnread()
    } catch (e) {
      this.setData({ firstLoad: false })
    } finally {
      this.setData({ loading: false })
    }
  },

  async refreshUnread() {
    try {
      const res = await request.get('/notification/unread-count', undefined, { silent: true })
      const unread = res.count || 0
      this.setData({ unread })
      if (unread > 0) {
        wx.setTabBarBadge({ index: 3, text: String(unread > 99 ? '99+' : unread) })
      } else {
        wx.removeTabBarBadge({ index: 3 })
      }
    } catch (e) {
      /* 静默 */
    }
  },

  async onNoticeTap(e) {
    const idx = e.currentTarget.dataset.index
    const n = this.data.notices[idx]
    if (!n) return
    if (!n.isRead) {
      try {
        await request.post('/notification/read', { id: n.id }, { silent: true })
        this.setData({ ['notices[' + idx + '].isRead']: 1 })
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
          this.reload()
        } catch (e) {
          /* toast 已处理 */
        }
      },
    })
  },

  // ===== 公告 =====
  async loadAnnounces() {
    const { page } = this.data
    this.setData({ loading: true })
    try {
      const res = await request.get('/announcement/list', { page, size: PAGE_SIZE })
      const items = (res.list || []).map((a) => ({ ...a, timeText: fmtDateTime(a.publishTime) }))
      const all = page === 1 ? items : this.data.items.concat(items)
      this.setData({
        items: all,
        firstLoad: false,
        finished: all.length >= (res.total || 0),
      })
    } catch (e) {
      this.setData({ firstLoad: false })
    } finally {
      this.setData({ loading: false })
    }
  },
  loadMore() {
    if (this.data.finished || this.data.loading) return
    this.setData({ page: this.data.page + 1 })
    this.loadAnnounces()
  },
  goAnnounce(e) {
    wx.navigateTo({ url: '/pages/message/notice-detail?id=' + e.currentTarget.dataset.id })
  },
})
