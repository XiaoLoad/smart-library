// 智慧图书借阅管理系统 · 读者端小程序（原生）
const auth = require('./utils/auth')
const request = require('./utils/request')
const badge = require('./utils/badge')
const report = require('./utils/report')

App({
  globalData: {
    user: null, // UserVo: {id, username, realName, userType, roleName, avatar}
  },

  onLaunch() {
    const user = auth.getUser()
    if (user) this.globalData.user = user
    this.silentCheck()
  },

  // 前台恢复时对齐未读角标；未登录由 badge 内部自行跳过，不发请求
  onShow() {
    badge.refresh()
  },

  // 兜底：漏网的脚本异常与未处理的 Promise 拒绝都汇总到一处，走 utils/report 上报
  onError(err) {
    report.error('app.onError', err)
  },
  onUnhandledRejection(res) {
    report.error('app.onUnhandledRejection', res && res.reason)
  },

  // 静默验活：token 失效时 request.js 会自动清理并跳登录页
  silentCheck() {
    if (!auth.getToken()) return
    request
      .get('/user/info')
      .then((data) => {
        const u = (data && data.user) || data
        if (u && u.id) {
          this.globalData.user = u
          auth.saveUser(u)
        }
      })
      .catch(() => {})
  },
})
