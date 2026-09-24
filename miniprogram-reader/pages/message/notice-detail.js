const request = require('../../utils/core/request')
const { fmtDateTime } = require('../../utils/feature/datetime')

Page({
  data: { item: null, timeText: '' },

  onLoad(options) {
    this.load(options.id)
  },
  onShareAppMessage() {
    const a = this.data.item
    return { title: a ? a.title : '图书馆公告', path: '/pages/message/notice-detail?id=' + (a ? a.id : '') }
  },

  async load(id) {
    try {
      const item = await request.get('/announcement/detail', { id })
      this.setData({ item, timeText: fmtDateTime(item.publishTime) })
      wx.setNavigationBarTitle({ title: item.title || '公告详情' })
    } catch (e) {
      /* toast 已处理 */
    }
  },
})
