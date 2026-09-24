/* ===== 统一错误上报 =====
 * 出口只有一个：微信「实时日志」。开发者工具在「调试器 → 实时日志」看，
 * 线上在小程序后台 → 开发管理 → 运维中心 → 实时日志，可按页面 / openid 检索。
 * 低版本基础库没有 getRealtimeLogManager 时退回 console——错误上报本身不能成为新的故障点，
 * 所以这里不发任何网络请求。
 *
 * 采样：同一条错误（按 tag + 描述）一次会话最多报 3 次，总量上限 30 条。
 * 循环里抛出的错会以毫秒级重复刷屏，全量上报既没有信息量，也容易被日志侧限流。
 */
const MAX_PER_MESSAGE = 3
const MAX_TOTAL = 30

let total = 0
const seen = {}
let env = null

/** 报错必备的三件事：哪台设备、哪个基础库、哪个页面——真机独有的问题全靠它们复现 */
function envOf() {
  if (env) return env
  try {
    const dev = wx.getDeviceInfo ? wx.getDeviceInfo() : wx.getSystemInfoSync()
    const base = wx.getAppBaseInfo ? wx.getAppBaseInfo() : wx.getSystemInfoSync()
    env = {
      model: (dev && dev.model) || '',
      platform: (dev && dev.platform) || '',
      sdk: (base && base.SDKVersion) || '',
    }
  } catch (e) {
    env = { model: '', platform: '', sdk: '' }
  }
  return env
}

function currentPage() {
  try {
    const pages = getCurrentPages()
    const cur = pages[pages.length - 1]
    return (cur && cur.route) || ''
  } catch (e) {
    return ''
  }
}

/** 把任意 throw 出来的东西压成一行短描述：太长会挤掉日志侧的检索信息 */
function describe(err) {
  if (!err) return ''
  if (typeof err === 'string') return err.slice(0, 300)
  const msg = err.errMsg || err.message || String(err)
  const stack = err.stack ? String(err.stack).split('\n').slice(0, 3).join(' | ') : ''
  return (msg + (stack ? ' @ ' + stack : '')).slice(0, 500)
}

function send(level, tag, message, extra) {
  const msg = message || '(无描述)'
  const key = tag + '|' + msg
  const hop = seen[key] || 0
  if (hop >= MAX_PER_MESSAGE || total >= MAX_TOTAL) return
  seen[key] = hop + 1
  total += 1

  const payload = Object.assign(
    { tag, msg, hop: hop + 1, page: currentPage() },
    envOf(),
    extra || {},
  )

  const log = wx.getRealtimeLogManager ? safeLog() : null
  if (log) {
    try {
      if (log.addFilterMsg) log.addFilterMsg(tag)
      if (level === 'warn' && log.warn) log.warn(payload)
      else log.error(payload)
      return
    } catch (e) {
      // 落到 console
    }
  }
  const line = '[report] ' + JSON.stringify(payload)
  if (level === 'warn') console.warn(line)
  else console.error(line)
}

function safeLog() {
  try {
    return wx.getRealtimeLogManager()
  } catch (e) {
    return null
  }
}

module.exports = {
  error(tag, err, extra) {
    send('error', tag, describe(err), extra)
  },
  warn(tag, err, extra) {
    send('warn', tag, describe(err), extra)
  },
}