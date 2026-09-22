const request = require('../../utils/request')
const cover = require('../../utils/cover')

const PAGE_SIZE = 12 // 3 列布局，12 本正好 4 排
const CHUNK = 3

/**
 * 书名只留正题：3 列窄栏一行放得下约 9 字，而全库书名平均 18.6 字、59% 超过 12 字，
 * 副题（全角括号/破折号之后的内容）留在详情页看，列表里一律截断。
 */
function displayTitle(raw) {
  if (!raw) return ''
  let t = String(raw)
    .replace(/&#(\d+);/g, (m, d) => String.fromCharCode(Number(d)))
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
  t = t.replace(/^《([^》]*)》[\s\S]*$/, '$1')
  const stop = t.search(/[（(【\[]/)
  if (stop > 0) t = t.slice(0, stop)
  t = t.split('——')[0]
  t = t.replace(/[（(【\[][\s\S]*$/, '').trim()
  return t || String(raw).trim()
}

/** 状态三档：可借 / 仅余 1 / 已借完，另留一档兜底下架 */
function stateOf(book) {
  if (book.status === 0) return { stateText: '已下架', stateClass: 'st-gray' }
  const available = book.availableCount || 0
  if (available <= 0) return { stateText: '已借完', stateClass: 'st-danger' }
  if (available === 1) return { stateText: '仅余 1', stateClass: 'st-warn' }
  return { stateText: '可借 ' + available, stateClass: 'st-ok' }
}

/** 排内条目补上渲染地址。封面只留在 rows 里——list 与 rows 各存一份的话，
 *  setData 序列化时同一张图会被写两遍。书库网格走 CSS 背景图（B 通道），
 *  用直连地址即可，零数据量、无翻页上限；详情/头像才走 base64。 */
function withCover(item) {
  return Object.assign({}, item, { cover: cover.bgSrc(item.coverUrl) })
}

function toRows(list) {
  const rows = []
  for (let i = 0; i < list.length; i += CHUNK) {
    rows.push({ key: 'r' + i / CHUNK, items: list.slice(i, i + CHUNK).map(withCover) })
  }
  return rows
}

/** 分类树压平成渲染列表：剔停用节点，按展开态只下钻已展开的分支 */
function flattenTree(nodes, expanded, out, lv) {
  ;(nodes || []).forEach((n) => {
    if (n.status === 0) return
    const children = (n.children || []).filter((c) => c.status !== 0)
    const open = expanded[n.id] === true
    out.push({
      id: n.id,
      name: n.categoryName || '',
      count: n.bookCount || 0,
      lv,
      pad: lv === 1 ? 36 : lv === 2 ? 72 : 108,
      hasChildren: children.length > 0,
      open,
    })
    if (children.length && open) flattenTree(children, expanded, out, lv + 1)
  })
  return out
}

Page({
  data: {
    keyword: '',
    list: [],
    rows: [],
    page: 1,
    total: 0,
    countText: '',
    loading: false,
    finished: false,
    firstLoad: true,

    catOpen: false,
    catLoaded: false,
    catNodes: [],
    expandedIds: {},
    catSelId: 0,
    catSelName: '',

    catHeight: 72,
    catDragging: false,
  },

  onLoad() {
    try {
      const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {}
      this._winH = info.windowHeight || 0
    } catch (e) {
      this._winH = 0
    }
    this.loadCategories()
    this.reload()
  },
  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 })
    }
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
  onClearKeyword() {
    clearTimeout(this._debounce)
    this.setData({ keyword: '' })
    this.reload()
  },

  /* —— 分类树 —— */
  async openCat() {
    if (!this.data.catLoaded) await this.loadCategories()
    if (!this.data.catLoaded) return
    this.setData({ catOpen: true, catHeight: 72 })
  },
  closeCat() {
    this.setData({ catOpen: false })
  },

  /* 拖动条手势：底部锚定，向上拉高（展开）、向下拉低（关闭）。
     几何量 px→vh 用 onLoad 缓存的窗口高换算；松手按落点高度就近吸附。 */
  onCatGripStart(e) {
    if (!this.data.catOpen) return
    const t = e.touches[0]
    if (!t) return
    this._grip = { startY: t.clientY, startH: this.data.catHeight, curH: this.data.catHeight }
    this.setData({ catDragging: true })
  },
  onCatGripMove(e) {
    const g = this._grip
    if (!g) return
    const t = e.touches[0]
    if (!t) return
    const dy = t.clientY - g.startY // px，正值向下
    const winH = this._winH || 700
    let h = g.startH - (dy / winH) * 100
    if (h < 20) h = 20
    else if (h > 100) h = 100
    g.curH = h
    this.setData({ catHeight: h })
  },
  onCatGripEnd() {
    const g = this._grip
    if (!g) return
    this._grip = null
    const h = g.curH
    this.setData({ catDragging: false })
    if (h < 45) {
      this.closeCat() // 拉到足够低 → 关闭
    } else if (h >= 84) {
      this.setData({ catHeight: 92 }) // 展开态
    } else {
      this.setData({ catHeight: 72 }) // 回落到默认折叠态
    }
  },
  onCatToggle(e) {
    const id = e.currentTarget.dataset.id
    const expanded = Object.assign({}, this.data.expandedIds)
    expanded[id] = !expanded[id]
    this.setData({ expandedIds: expanded, catNodes: flattenTree(this._tree, expanded, [], 1) })
  },
  onCatSelect(e) {
    const id = Number(e.currentTarget.dataset.id)
    const node = this.data.catNodes.filter((n) => n.id === id)[0]
    if (!node) return
    if (!node.count) {
      wx.showToast({ title: '该分类暂无在架图书', icon: 'none' })
      return
    }
    this.setData({ catOpen: false, catSelId: id, catSelName: node.name })
    this.reload()
  },
  onCatReset() {
    if (!this.data.catSelId) {
      this.setData({ catOpen: false })
      return
    }
    this.setData({ catOpen: false, catSelId: 0, catSelName: '' })
    this.reload()
  },

  async loadCategories() {
    try {
      const tree = await request.get('/book/category/tree', undefined, { silent: true })
      const list = Array.isArray(tree) ? tree : (tree && tree.list) || []
      this._tree = list
      // 默认展开第一个有子节点的一级分类，免得打开抽屉只是六个光杆
      const expanded = {}
      const first = list.filter((n) => n.status !== 0 && (n.children || []).length)[0]
      if (first) expanded[first.id] = true
      this.setData({
        catLoaded: true,
        expandedIds: expanded,
        catNodes: flattenTree(list, expanded, [], 1),
      })
    } catch (e) {
      wx.showToast({ title: '分类加载失败', icon: 'none' })
    }
  },

  /* —— 书目 —— */
  reload() {
    this.setData({ page: 1, finished: false, list: [], rows: [] })
    return this.load()
  },
  loadMore() {
    if (this.data.finished || this.data.loading) return
    this.setData({ page: this.data.page + 1 })
    this.load()
  },
  async load() {
    const { keyword, catSelId, page } = this.data
    this.setData({ loading: true })
    try {
      const res = await request.get('/book/list', {
        page,
        size: PAGE_SIZE,
        keyword,
        categoryId: catSelId || undefined,
      })
      const items = (res.list || []).map((b) => {
        const state = stateOf(b)
        return Object.assign({}, b, {
          displayTitle: displayTitle(b.title),
          stateText: state.stateText,
          stateClass: state.stateClass,
        })
      })
      const list = page === 1 ? items : this.data.list.concat(items)
      const rows = toRows(list)
      const total = res.total || 0
      this.setData({
        list,
        rows,
        total,
        countText: total ? (this.data.catSelName ? '共 ' + total + ' 种 · ' + this.data.catSelName : '共 ' + total + ' 种馆藏') : '',
        firstLoad: false,
        finished: list.length >= total,
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

  /** 遮罩上吞掉手势，避免抽屉打开时背后列表跟着滚 */
  noop() {},
})
