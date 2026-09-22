const request = require('../../utils/request')

Page({
  data: { quota: null, loading: true },

  onLoad() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    try {
      const quota = await request.get('/borrow/quota')
      this.setData({ quota, loading: false })
    } catch (e) {
      this.setData({ loading: false })
    }
  },
})
