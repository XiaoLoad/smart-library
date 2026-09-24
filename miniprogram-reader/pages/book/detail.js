const request = require('../../utils/core/request')
const cover = require('../../utils/feature/cover')
const { fmtDate } = require('../../utils/feature/datetime')

Page({
  data: {
    book: null,
    cover: '',
    loading: true,
    quota: null,
    borrowing: false,
    blockReason: '',
    canBorrow: false,
    coverChar: '',
    descHead: '',
    descRest: '',
    priceNum: '',
    stockTicks: [],
  },

  onLoad(options) {
    this.bookId = options.id
    this.load()
  },

  // 借阅成功后跳回或再次进入本页时刷新库存与额度；失败保留已渲染的详情
  onShow() {
    if (this.data.book) this.refresh()
  },

  async load() {
    try {
      const book = await request.get('/book/' + this.bookId)
      this.setData(
        Object.assign(
          {
            book,
            cover: cover.coverSrc(book.coverUrl),
            pubDateText: fmtDate(book.publishDate),
            loading: false,
          },
          this.plateFields(book)
        )
      )
      wx.setNavigationBarTitle({ title: book.title || '图书详情' })
      this.fillCover(book.coverUrl)
    } catch (e) {
      this.setData({ loading: false })
      return
    }
    await this.loadQuota()
  },

  async refresh() {
    try {
      const [book, quota] = await Promise.all([
        request.get('/book/' + this.bookId, undefined, { silent: true }),
        request.get('/borrow/quota', undefined, { silent: true }),
      ])
      this.setData(
        Object.assign(
          { book, quota, cover: cover.coverSrc(book.coverUrl) },
          this.plateFields(book)
        )
      )
      this.fillCover(book.coverUrl)
    } catch (e) {
      /* 静默：不把已渲染的详情打回空态 */
    }
    this.syncBorrowState()
  },

  /** 藏书票派生数据：无封面时的首字、简介首字下沉、定价、库存刻度片 */
  plateFields(book) {
    const total = book.totalCount || 0
    const cells = Math.min(total, 10)
    const lit = total ? Math.round(((book.availableCount || 0) / total) * cells) : 0
    const desc = book.description || ''
    const isCjk = /^[\u4e00-\u9fa5]/.test(desc)
    return {
      coverChar: (book.title || '').charAt(0),
      descHead: isCjk ? desc.charAt(0) : '',
      descRest: isCjk ? desc.slice(1) : desc,
      priceNum: Number(book.price || 0).toFixed(2),
      stockTicks: Array.from({ length: cells }, (_, i) => ({ i, on: i < lit })),
    }
  },

  /** 封面按需编码：编码回来后就地替换 */
  async fillCover(uri) {
    if (await cover.prefetch([uri])) {
      this.setData({ cover: cover.coverSrc(uri) })
    }
  },

  async loadQuota() {
    try {
      const quota = await request.get('/borrow/quota', undefined, { silent: true })
      this.setData({ quota })
    } catch (e) {
      /* 额度取不到时不做前端预判，交由后端兜底 */
    }
    this.syncBorrowState()
  },

  // 不可借原因的判断顺序与后端 BorrowService.apply 一致：额度 → 逾期 → 库存
  syncBorrowState() {
    const { book, quota } = this.data
    if (!book) {
      this.setData({ blockReason: '', canBorrow: false })
      return
    }
    let reason = ''
    if (book.status === 0) {
      reason = '该图书已下架'
    } else if (quota) {
      const max = quota.maxBooks || 0
      const used = quota.used || 0
      if (max === 0) reason = '当前账号无借阅权限'
      else if (used >= max) reason = '借阅额度已用满（' + used + '/' + max + '），请先归还'
      else if ((quota.overdueCount || 0) > 0)
        reason = '您有 ' + quota.overdueCount + ' 本图书已逾期，归还后方可继续借阅'
      else if (book.availableCount <= 0) reason = '该图书当前无可借复本'
    }
    this.setData({ blockReason: reason, canBorrow: !reason })
  },

  onBorrow() {
    const b = this.data.book
    if (!b || this.data.borrowing) return
    if (!this.data.canBorrow) {
      wx.showToast({ title: this.data.blockReason || '暂不可借', icon: 'none' })
      return
    }
    wx.showModal({
      title: '申请借阅',
      content: '确认借阅《' + b.title + '》？',
      confirmText: '确认借阅',
      success: (r) => {
        if (!r.confirm) return
        this.submitBorrow(b)
      },
    })
  },

  async submitBorrow(book) {
    this.setData({ borrowing: true })
    try {
      await request.post('/borrow/apply', { bookId: book.id })
    } catch (e) {
      this.setData({ borrowing: false })
      return
    }
    wx.showToast({ title: '借阅申请成功', icon: 'success' })
    // 锁保持到跳转为止，避免 toast 的 800ms 窗口里连点提交多笔
    setTimeout(() => {
      this.setData({ borrowing: false })
      wx.switchTab({ url: '/pages/borrow/index' })
    }, 800)
  },
})
