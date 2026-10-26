/* 骨架屏
 * 与真实版式同构的灰块：书库三列封面、记录卡、通知条、公告条四种版式，
 * 让「正在加载」这段留白有形状，数据落位时不会整体跳一下。
 * 各页用法都是静态传参（variant / count 一次定死），故只在 attached 里算一次。
 */
Component({
  properties: {
    variant: { type: String, value: 'card' }, // shelf | card | notice | announce
    count: { type: Number, value: 3 },
  },

  data: { shelfRows: [], items: [] },

  attached() {
    const n = Math.max(1, Math.min(20, this.data.count || 3))
    if (this.data.variant === 'shelf') {
      const shelfRows = []
      for (let i = 0; i < n; i++) {
        shelfRows.push({ key: 'r' + i, items: [0, 1, 2] })
      }
      this.setData({ shelfRows, items: [] })
      return
    }
    const items = []
    for (let i = 0; i < n; i++) items.push(i)
    this.setData({ items, shelfRows: [] })
  },
})