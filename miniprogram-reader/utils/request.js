/* ===== wx.request 封装：token 注入 + {code,message,data} 信封解析 =====
 * 与后端约定：业务成功 code===200；401 也走 HTTP 200 + body.code=401（JwtInterceptor 行为）
 */
const { BASE_URL } = require('./config')
const auth = require('./auth')

let redirecting = false
function toLoginOnce() {
  if (redirecting) return
  redirecting = true
  wx.navigateTo({
    url: '/pages/auth/login',
    complete: () => {
      setTimeout(() => (redirecting = false), 800)
    },
  })
}

function buildQuery(params) {
  if (!params) return ''
  const parts = []
  Object.keys(params).forEach((k) => {
    const v = params[k]
    if (v === undefined || v === null || v === '') return
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
  })
  return parts.length ? '?' + parts.join('&') : ''
}

/**
 * @returns {Promise<any>} 成功时 resolve data（data 为 null 时 resolve {message}）
 */
function request(method, url, data, opts = {}) {
  return new Promise((resolve, reject) => {
    const header = {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + auth.getToken(),
    }
    const isGet = method === 'GET'
    wx.request({
      url: BASE_URL + url + (isGet ? buildQuery(data) : ''),
      method,
      data: isGet ? undefined : data,
      header,
      timeout: opts.timeout || 10000,
      success(res) {
        const body = res.data || {}
        if (body.code === 200) {
          resolve(body.data === null ? { message: body.message } : body.data)
          return
        }
        if (body.code === 401) {
          auth.clear()
          wx.showToast({ title: '请先登录', icon: 'none' })
          toLoginOnce()
          reject(new Error(body.message || '未登录'))
          return
        }
        if (!opts.silent) {
          wx.showToast({ title: body.message || '请求失败', icon: 'none' })
        }
        reject(new Error(body.message || '请求失败'))
      },
      fail(err) {
        if (!opts.silent) {
          wx.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        }
        reject(new Error(err.errMsg || '网络异常'))
      },
    })
  })
}

module.exports = {
  get: (url, params, opts) => request('GET', url, params, opts),
  post: (url, data, opts) => request('POST', url, data, opts),
  del: (url, opts) => request('DELETE', url, undefined, opts),
  request,
}
