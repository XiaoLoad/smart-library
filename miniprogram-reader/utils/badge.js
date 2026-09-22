/* ===== tabBar 未读角标 =====
 * 未读数只有一个出口：谁拿到它都往这里送，避免多处各自 setTabBarBadge 导致状态打架。
 * 三条约束：
 * 1) 未登录时清角标且不发请求——/notification/unread-count 返 401 时 request.js 会清会话
 *    并跳登录页，后台静默刷新绝不能把用户踢出去。
 * 2) 并发调用共用同一个请求（inflight 去重），App.onShow 与消息页 onShow 先后触发时只打一次。
 * 3) 拉取失败保留上一次角标，不因一次网络抖动把未读提示抹掉。
 */
const TAB_INDEX = 3 // tabBar.list 顺序：书库 0 / 借阅 1 / AI 助手 2 / 消息 3 / 我的 4

let shown = null // 当前已画出的未读数；null 表示未知（还没成功取过）
let inflight = null

function paint(count) {
  const n = count > 0 ? count : 0
  shown = n
  if (n > 0) {
    wx.setTabBarBadge({ index: TAB_INDEX, text: String(n > 99 ? '99+' : n), fail() {} })
  } else {
    wx.removeTabBarBadge({ index: TAB_INDEX, fail() {} })
  }
  syncCustomBar(n)
  return n
}

// 清角标：登出、会话失效、确认未登录
function dismiss() {
  shown = null
  inflight = null
  wx.removeTabBarBadge({ index: TAB_INDEX, fail() {} })
  syncCustomBar(0)
}

// 自定义 tabBar 下 wx.setTabBarBadge 失效，改由 custom-tab-bar 组件自绘角标
function syncCustomBar(n) {
  try {
    const pages = getCurrentPages()
    const cur = pages[pages.length - 1]
    const bar = cur && typeof cur.getTabBar === 'function' && cur.getTabBar()
    if (bar && bar.setData) bar.setData({ unread: n })
  } catch (e) {}
}

// 拉一次未读数并刷新角标，resolve 当前未读数（失败时给上一次的值）
function refresh() {
  const auth = require('./auth')
  if (!auth.getToken()) {
    if (shown !== null) dismiss()
    return Promise.resolve(0)
  }
  if (inflight) return inflight
  const request = require('./request')
  inflight = request
    .get('/notification/unread-count', undefined, { silent: true })
    .then((res) => paint(Number(res && res.count) || 0))
    .catch(() => (shown === null ? 0 : shown))
    .then((n) => {
      inflight = null
      return n
    })
  return inflight
}

module.exports = { refresh, dismiss, TAB_INDEX }
