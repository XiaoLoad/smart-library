const request = require('../../utils/request')
const qr = require('../../utils/qrcode')
const { fmtDateTime } = require('../../utils/datetime')

Page({
  data: { record: null, qrSize: 480 },

  onLoad(options) {
    this.borrowNo = options.borrowNo || ''
    // 保持屏幕常亮方便扫码
    wx.setKeepScreenOn({ keepScreenOn: true })
    // 自动提亮：先记下当前亮度，离开时原样恢复；get 失败则不恢复
    wx.getScreenBrightness({
      success: (res) => {
        this._prevBrightness = res.value
      },
    })
    wx.setScreenBrightness({ value: 1 })
    // 优先取列表页透传的记录；事件通道的回调晚于 onLoad，延后一拍再决定是否兜底请求
    const ec = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (ec) {
      ec.on('record', (rec) => {
        if (rec) this.applyRecord(rec)
      })
    }
    setTimeout(() => {
      if (!this.data.record) this.fetchByBorrowNo()
    }, 100)
  },

  onUnload() {
    wx.setKeepScreenOn({ keepScreenOn: false })
    if (typeof this._prevBrightness === 'number') {
      wx.setScreenBrightness({ value: this._prevBrightness })
    }
  },

  async fetchByBorrowNo() {
    if (!this.borrowNo) return
    try {
      const res = await request.get('/borrow/list', { page: 1, size: 50, search: this.borrowNo })
      const rec = (res.list || []).find((r) => r.borrowNo === this.borrowNo) || (res.list || [])[0]
      if (rec) this.applyRecord(rec)
    } catch (e) {
      wx.showToast({ title: '借阅记录加载失败', icon: 'none' })
    }
  },

  applyRecord(rec) {
    this.setData({ record: rec, pickupText: fmtDateTime(rec.pickupDeadline) })
    wx.setNavigationBarTitle({ title: '取书码 ' + rec.borrowNo })
    // 数据渲染后再画码
    wx.nextTick(() => this.drawQr(rec.qrPayload || rec.borrowNo))
  },

  drawQr(payload) {
    wx.createSelectorQuery()
      .select('#qrCanvas')
      .node()
      .exec((res) => {
        const node = res && res[0] && res[0].node
        const ok = node && qr.draw(node, payload, this.data.qrSize / 2)
        if (!ok) {
          this.setData({ qrFailed: true })
          wx.showToast({ title: '二维码组件未就绪（请构建 npm）', icon: 'none' })
        }
      })
  },

  onPreview() {
    wx.showModal({
      title: '扫码提示',
      content: '屏幕亮度已自动调至最高，请对准管理员扫码设备。如仍无法识别，可报借阅单号手动处理。',
      showCancel: false,
    })
  },
})
