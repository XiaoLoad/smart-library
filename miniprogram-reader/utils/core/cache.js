/* ===== 列表缓存 · stale-while-revalidate =====
 * 冷启动先把上一次的这一屏铺上（stale），再静默向服务端要新数据替换（revalidate），
 * 免掉「白屏 → 数据」的跳变。缓存只是先手的占位，权威数据永远是服务端那一份。
 *
 * 两条约束：
 * 1) 键里带 userId——同一台设备换账号登录时，绝不能读到上一个人的借阅记录；
 * 2) 只缓存列表首页（page=1）。翻页数据只在当次会话里有意义，留着就要和可能已被
 *    后台改动的旧页拼接，得不偿失。
 */
const auth = require('./auth')

const PREFIX = 'swr:'
const TTL = 6 * 60 * 60 * 1000 // 超过 6 小时视为过期：太旧的屏不如直接等网络

function keyOf(name) {
  const u = auth.getUser()
  return PREFIX + name + '@' + ((u && u.id) || 0)
}

/** 读缓存，未命中/已过期/结构异常一律返回 null */
function read(name) {
  try {
    const box = wx.getStorageSync(keyOf(name))
    if (!box || !box.t || !box.d) return null
    if (Date.now() - box.t > TTL) return null
    return box.d
  } catch (e) {
    return null
  }
}

function write(name, data) {
  try {
    wx.setStorageSync(keyOf(name), { t: Date.now(), d: data })
  } catch (e) {
    // 存储写满等异常不该影响列表渲染
  }
}

module.exports = { read, write }