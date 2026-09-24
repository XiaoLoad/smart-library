const request = require('../../utils/core/request')
const auth = require('../../utils/core/auth')
const badge = require('../../utils/feature/badge')

function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (r) => (r.code ? resolve(r.code) : reject(new Error('wx.login 未返回 code'))),
      fail: reject,
    })
  })
}

Page({
  data: { username: '', password: '', remember: true, loading: false },

  onLoad() {
    const u = auth.getRememberedUsername()
    if (u) this.setData({ username: u })
  },

  onUsername(e) { this.setData({ username: e.detail.value }) },
  onPassword(e) { this.setData({ password: e.detail.value }) },
  toggleRemember() { this.setData({ remember: !this.data.remember }) },

  async onLogin() {
    const { username, password, remember } = this.data
    if (!username || !password) {
      wx.showToast({ title: '请输入账号和密码', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      const res = await request.post('/auth/login', { username, password })
      auth.saveToken(res.token)
      auth.saveUser(res.user)
      if (remember) auth.rememberUsername(username)
      else auth.forgetUsername()
      await this.maybeBindWechat()
      badge.refresh()
      wx.switchTab({ url: '/pages/book/index' })
    } catch (e) {
      /* 错误提示已由 request 统一处理 */
    } finally {
      this.setData({ loading: false })
    }
  },

  // 首次登录后引导绑定微信，后续可一键登录
  maybeBindWechat() {
    return request
      .get('/auth/wechat-status', undefined, { silent: true })
      .then((d) => {
        if (d && d.bound) return
        return new Promise((resolve) => {
          wx.showModal({
            title: '绑定微信',
            content: '绑定当前微信后，下次可一键登录。是否立即绑定？',
            confirmText: '绑定',
            cancelText: '暂不',
            success: async (r) => {
              if (r.confirm) {
                try {
                  const code = await wxLogin()
                  await request.post('/auth/wechat-bind', { code })
                  wx.showToast({ title: '微信绑定成功', icon: 'success' })
                } catch (e) {
                  /* toast 已处理 */
                }
              }
              resolve()
            },
            fail: resolve,
          })
        })
      })
      .catch(() => {})
  },

  async onWechatLogin() {
    if (this.data.loading) return
    this.setData({ loading: true })
    try {
      const code = await wxLogin()
      const res = await request.post('/auth/wechat-login', { code })
      auth.saveToken(res.token)
      auth.saveUser(res.user)
      badge.refresh()
      wx.switchTab({ url: '/pages/book/index' })
    } catch (e) {
      /* 未绑定等业务错误 toast 已由 request 处理 */
    } finally {
      this.setData({ loading: false })
    }
  },
})
