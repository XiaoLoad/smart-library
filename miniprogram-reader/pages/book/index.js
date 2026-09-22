const request = require('../../utils/request')
const { resolveUrl } = require('../../utils/config')

const PAGE_SIZE = 10

Page({
  data: {
    keyword: '',
    categories: [{ id: 0, label: '全部分类' }],
    categoryIndex: 0,
    list: [],
    page: 1,
    total: 0,
    loading: false,
    finished: false,
    firstLoad: true,
  },

  onLoad() {
    this.loadCategories()
    this.reload()
  },
  onPullDownRefresh() {
    this.reload().finally(() => wx.stopPullDownRefresh())
  },
  onReachBottom() {
    this.loadMore()
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
    clearTimeout(this._debounce)
    this._debounce = setTimeout(() => this.reload(), 300)
  },
  onCategoryChange(e) {
    this.setData({ categoryIndex: Number(e.detail.value) })
    this.reload()
  },

  async loadCategories() {
    try {
      const tree = await request.get('/book/category/tree', undefined, { silent: true })
      const flat = [{ id: 0, label: '全部分类' }]
      const walk = (nodes, depth) =>
        (nodes || []).forEach((n) => {
          flat.push({ id: n.id, label: '\u3000'.repeat(depth) + (n.categoryName || '') })
          walk(n.children || n.childList, depth + 1)
        })
      walk(Array.isArray(tree) ? tree : (tree && tree.list) || [], 0)
      this.setData({ categories: flat })
    } catch (e) {
      /* 筛选不可用不影响列表 */
    }
  },

  reload() {
    this.setData({ page: 1, finished: false, list: [] })
    return this.load()
  },
  loadMore() {
    if (this.data.finished || this.data.loading) return
    this.setData({ page: this.data.page + 1 })
    this.load()
  },
  async load() {
    const { keyword, categories, categoryIndex, page } = this.data
    const categoryId = categories[categoryIndex].id
    this.setData({ loading: true })
    try {
      const res = await request.get('/book/list', {
        page,
        size: PAGE_SIZE,
        keyword,
        categoryId: categoryId || undefined,
      })
      const items = (res.list || []).map((b) => ({ ...b, cover: resolveUrl(b.coverUrl) }))
      const list = page === 1 ? items : this.data.list.concat(items)
      this.setData({
        list,
        total: res.total || 0,
        firstLoad: false,
        finished: list.length >= (res.total || 0),
      })
    } catch (e) {
      this.setData({ firstLoad: false })
    } finally {
      this.setData({ loading: false })
    }
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/book/detail?id=' + e.currentTarget.dataset.id })
  },
})
