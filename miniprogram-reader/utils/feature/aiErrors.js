/* AI 模块错误分类与文案分离（与 Web 端 utils/aiErrors.js 同一约定，小程序简化版）
 * classify 只归类不生成文案——调用方按类目自行措辞；
 * withRetry 指数退避重试（1s → 2s，最多 2 次），只对可重试类目生效。
 */

const Kind = {
  UNAUTHORIZED: 'unauthorized', // 未登录（request 层已统一跳登录）
  FORBIDDEN: 'forbidden', // 已登录但无权限
  RATE_LIMITED: 'rate_limited', // 限流 / 上游繁忙
  NETWORK: 'network', // 网络异常（可重试）
  NOT_FOUND: 'not_found', // 资源不存在
  INVALID: 'invalid', // 参数 / 业务校验拒绝
  SERVER: 'server', // 服务端 5xx（可重试一次）
  UNKNOWN: 'unknown',
}

/** 类目默认动作文案（「请稍后再试」式），与各场景自己的主文案拼接 */
const KIND_ACTION = {
  [Kind.UNAUTHORIZED]: '请重新登录',
  [Kind.FORBIDDEN]: '请检查账号权限',
  [Kind.RATE_LIMITED]: '请稍后再试',
  [Kind.NETWORK]: '请检查网络后重试',
  [Kind.NOT_FOUND]: '请刷新后重试',
  [Kind.INVALID]: '请检查输入后重试',
  [Kind.SERVER]: '请稍后再试',
  [Kind.UNKNOWN]: '请稍后再试',
}

/**
 * 错误归类。只判断类型，不产出面向用户的文案。
 * @param {Error|string} err 捕获到的异常
 * @param {{code?: number}} [meta] 后端业务码（可选）
 * @returns {{kind: string, message: string}}
 */
function classify(err, meta = {}) {
  const raw = String((err && err.message) || err || '')
  const code = meta.code != null ? meta.code : err && err.code
  if (code === 401 || /登录|token/i.test(raw)) return { kind: Kind.UNAUTHORIZED, message: raw }
  if (code === 403 || /无权|权限/.test(raw)) return { kind: Kind.FORBIDDEN, message: raw }
  if (code === 404 || /不存在|已删除/.test(raw)) return { kind: Kind.NOT_FOUND, message: raw }
  if (code === 429 || /繁忙|限流|频繁/.test(raw)) return { kind: Kind.RATE_LIMITED, message: raw }
  if (/网络|超时|timeout|fail/i.test(raw)) return { kind: Kind.NETWORK, message: raw }
  if (typeof code === 'number' && code >= 500) return { kind: Kind.SERVER, message: raw }
  if (code != null) return { kind: Kind.INVALID, message: raw }
  return { kind: Kind.UNKNOWN, message: raw }
}

/** 类目是否值得自动重试 */
function isRetryable(kind) {
  return kind === Kind.NETWORK || kind === Kind.RATE_LIMITED || kind === Kind.SERVER
}

/** 主文案 + 类目动作拼接（「xx，请稍后再试」） */
function describe(kind, message) {
  const action = KIND_ACTION[kind] || KIND_ACTION[Kind.UNKNOWN]
  const text = message || '请求失败'
  // 业务文案已带引导语时不重复拼接
  if (/请|建议|可以/.test(text)) return text
  return text + '，' + action
}

/**
 * 带指数退避的重试（1s → 2s，最多重试 2 次），仅可重试类目生效。
 * @param {() => Promise<any>} fn 执行体
 * @returns {Promise<any>} 全部失败时抛出最后一次异常（附 kind 字段）
 */
function withRetry(fn) {
  return new Promise((resolve, reject) => {
    let attempt = 0
    const run = () => {
      fn().then(resolve).catch((err) => {
        const kind = classify(err).kind
        if (!isRetryable(kind) || attempt >= 2) {
          err.kind = kind
          reject(err)
          return
        }
        setTimeout(run, 1000 * 2 ** attempt)
        attempt++
      })
    }
    run()
  })
}

module.exports = { Kind, classify, describe, isRetryable, withRetry }
