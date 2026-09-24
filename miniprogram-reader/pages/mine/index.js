const request = require('../../utils/core/request')
const auth = require('../../utils/core/auth')
const badge = require('../../utils/feature/badge')
const cover = require('../../utils/feature/cover')

Page({
  data: { user: null, avatar: '', wechatBound: false, wechatLoading: false },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 4 })
    }
    const user = auth.getUser() || (getApp().globalData.user || null)
    if (!user) {
      wx.navigateTo({ url: '/pages/auth/login' })
      return
    }
    this.setData({ user, avatar: cover.coverSrc(user.avatar) })
    this.fillAvatar(user.avatar)
    this.loadWechatStatus()
  },

  /** 头像与封面同走一条通道：编码回来后就地替换 */
  async fillAvatar(uri) {
    if (await cover.prefetch([uri])) {
      this.setData({ avatar: cover.coverSrc(uri) })
    }
  },

  async loadWechatStatus() {
    try {
      const d = await request.get('/auth/wechat-status', undefined, { silent: true })
      this.setData({ wechatBound: !!(d && d.bound) })
    } catch (e) {
      /* 静默 */
    }
  },

  go(e) {
    wx.navigateTo({ url: e.currentTarget.dataset.url })
  },

  // AI 助手为 tabBar 页，必须 switchTab
  goAi() {
    wx.switchTab({ url: '/pages/ai/index' })
  },

  onWechatTap() {
    if (this.data.wechatLoading) return
    this.data.wechatBound ? this.unbindWechat() : this.bindWechat()
  },

  bindWechat() {
    wx.showModal({
      title: '绑定微信',
      content: '绑定当前微信后，下次可直接微信一键登录。',
      confirmText: '绑定',
      success: async (r) => {
        if (!r.confirm) return
        this.setData({ wechatLoading: true })
        try {
          const code = await new Promise((resolve, reject) =>
            wx.login({ success: (res) => (res.code ? resolve(res.code) : reject(new Error('无 code'))), fail: reject })
          )
          await request.post('/auth/wechat-bind', { code })
          wx.showToast({ title: '绑定成功', icon: 'success' })
          this.setData({ wechatBound: true })
        } catch (e) {
          /* toast 已处理 */
        } finally {
          this.setData({ wechatLoading: false })
        }
      },
    })
  },

  unbindWechat() {
    wx.showModal({
      title: '解绑微信',
      content: '解绑后将无法使用微信一键登录，需用账号密码登录。确认解绑？',
      confirmText: '解绑',
      confirmColor: '#d93025',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await request.post('/auth/wechat-unbind')
          wx.showToast({ title: '已解绑', icon: 'success' })
          this.setData({ wechatBound: false })
        } catch (e) {
          /* toast 已处理 */
        }
      },
    })
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确认退出当前账号？',
      confirmText: '退出',
      confirmColor: '#d93025',
      success(r) {
        if (!r.confirm) return
        auth.clear()
        badge.dismiss()
        wx.reLaunch({ url: '/pages/auth/login' })
      },
    })
  },
})
