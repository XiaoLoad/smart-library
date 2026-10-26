/* 登录态存取 */
const TOKEN_KEY = 'library_token'
const USER_KEY = 'library_user'
const REMEMBER_KEY = 'library_remember_user'

module.exports = {
  getToken() {
    return wx.getStorageSync(TOKEN_KEY) || ''
  },
  saveToken(token) {
    wx.setStorageSync(TOKEN_KEY, token)
  },
  getUser() {
    return wx.getStorageSync(USER_KEY) || null
  },
  saveUser(user) {
    wx.setStorageSync(USER_KEY, user)
    const app = getApp()
    if (app) app.globalData.user = user
  },
  // 清会话（不清「记住的账号」）
  clear() {
    wx.removeStorageSync(TOKEN_KEY)
    wx.removeStorageSync(USER_KEY)
    const app = getApp()
    if (app) app.globalData.user = null
  },
  getRememberedUsername() {
    return wx.getStorageSync(REMEMBER_KEY) || ''
  },
  rememberUsername(username) {
    wx.setStorageSync(REMEMBER_KEY, username)
  },
  forgetUsername() {
    wx.removeStorageSync(REMEMBER_KEY)
  },
}
