// 智慧图书借阅管理系统 · 读者端小程序（原生）
const auth = require('./utils/auth')
const request = require('./utils/request')

App({
  globalData: {
    user: null, // UserVo: {id, username, realName, userType, roleName, avatar}
  },

  onLaunch() {
    const user = auth.getUser()
    if (user) this.globalData.user = user
    this.silentCheck()
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
