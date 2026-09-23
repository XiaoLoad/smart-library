// 分包内自带 utils/qrcode（底下是 55KB 的 qrcode-generator），跟着本页一起进分包，
// 不让它拖累主包；主包的工具（request/datetime）分包可以直接引用。
const request = require('../../utils/request')
const qr = require('../utils/qrcode')
const { fmtDateTime } = require('../../utils/datetime')

// 非「待取书」时给一句说明代替二维码：码在这个状态下已经没有意义，
// 而列表页那条记录可能是旧的（管理员刚核销完），所以要从服务端纠正过来。
const VOID_TEXT = {
  1: '该书已取走，取书码已作废',
  2: '该书已归还，取书码已作废',
  3: '该书已逾期，请到服务台办理归还',
  4: '已超过取书截止时间，借阅已自动取消',
  5: '已提交归还申请，等待管理员确认',
}
const VOID_DEFAULT = '该书当前状态已不能取书，取书码已作废'

Page({
  data: { record: null, canUse: false, voidText: '', qrSize: 480 },

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
    // 优先取列表页透传的记录先铺上，快到不用等请求；
    // 事件通道的回调晚于 onLoad，所以延后一拍再回源，让新鲜数据盖掉它
    const ec = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (ec) {
      ec.on('record', (rec) => {
        if (rec) this.applyRecord(rec)
      })
    }
    setTimeout(() => this.fetchByBorrowNo(), 100)
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
    const canUse = rec.status === 0
    this.setData({
      record: rec,
      canUse,
      voidText: canUse ? '' : VOID_TEXT[rec.status] || VOID_DEFAULT,
      pickupText: fmtDateTime(rec.pickupDeadline),
    })
    wx.setNavigationBarTitle({ title: (canUse ? '取书码 ' : '取书码已失效 ') + rec.borrowNo })
    // 数据渲染后再画码；码已作废就不画
    if (canUse) wx.nextTick(() => this.drawQr(rec.qrPayload || rec.borrowNo))
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

  onBack() {
    wx.navigateBack({ delta: 1 })
  },
})
