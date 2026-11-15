/**
 * AI 模块错误分类与文案分离（借鉴 deepseek-ai/chat-demo 的 errors/describe 分层）：
 * classify 只归类不生成文案——调用方按类目自行措辞，避免一个通用文案糊所有场景；
 * describeWithAction 给出「文案 + 建议动作」的默认组合，供列表加载等轻场景直接用。
 *
 * 另提供 withRetry：指数退避重试（2^n，上限 2 次），只对可重试类目生效——
 * 网络抖动/网关超时值得再试一次，权限不足/参数错误重试没有意义。
 */

/** 错误类目（闭合枚举，新增类目必须显式登记） */
export const AiErrorKind = {
  UNAUTHORIZED: 'unauthorized', // 未登录 / token 失效（调用方统一跳登录）
  FORBIDDEN: 'forbidden', // 已登录但无权限（跨用户会话、非管理员操作）
  RATE_LIMITED: 'rate_limited', // 限流 / 上游繁忙（可退避重试）
  NETWORK: 'network', // 网络异常 / 网关超时（可退避重试）
  NOT_FOUND: 'not_found', // 资源不存在（会话已删等，不可重试）
  INVALID: 'invalid', // 参数 / 业务校验拒绝（不可重试）
  SERVER: 'server', // 服务端 5xx / AI 渠道故障（可重试一次）
  UNKNOWN: 'unknown',
}

/** 各类目默认文案与建议动作（describeWithAction 用；调用方也可自带文案） */
const KIND_TEXT = {
  [AiErrorKind.UNAUTHORIZED]: { text: '登录已过期', action: '请重新登录' },
  [AiErrorKind.FORBIDDEN]: { text: '没有权限执行该操作', action: '请检查账号权限' },
  [AiErrorKind.RATE_LIMITED]: { text: 'AI 服务繁忙', action: '请稍后再试' },
  [AiErrorKind.NETWORK]: { text: '网络连接异常', action: '请检查网络后重试' },
  [AiErrorKind.NOT_FOUND]: { text: '内容不存在或已删除', action: '请刷新后重试' },
  [AiErrorKind.INVALID]: { text: '请求无法完成', action: '请检查输入后重试' },
  [AiErrorKind.SERVER]: { text: 'AI 服务暂时不可用', action: '请稍后再试' },
  [AiErrorKind.UNKNOWN]: { text: 'AI 请求失败', action: '请稍后再试' },
}

/**
 * 错误归类。只判断类型，不产出面向用户的文案。
 *
 * @param {Error|any} err     捕获到的异常
 * @param {{status?: number, code?: number|string}} [meta] HTTP 状态码 / 后端业务码（可选）
 * @returns {{kind: string, message: string}} kind 类目；message 原始错误文本（进日志/详情用）
 */
export const classifyAiError = (err, meta = {}) => {
  const raw = String(err?.message || err || '')
  const code = meta.code ?? err?.code
  const status = meta.status ?? err?.status

  if (code === 401 || status === 401 || /登录|token|unauthorized/i.test(raw)) {
    return { kind: AiErrorKind.UNAUTHORIZED, message: raw }
  }
  if (code === 403 || status === 403 || /无权|权限/.test(raw)) {
    return { kind: AiErrorKind.FORBIDDEN, message: raw }
  }
  if (code === 404 || status === 404 || /不存在|已删除/.test(raw)) {
    return { kind: AiErrorKind.NOT_FOUND, message: raw }
  }
  if (code === 429 || status === 429 || /繁忙|限流|频繁/.test(raw)) {
    return { kind: AiErrorKind.RATE_LIMITED, message: raw }
  }
  if (/network|fetch|Failed to fetch|超时|timeout|aborted/i.test(raw)) {
    return { kind: AiErrorKind.NETWORK, message: raw }
  }
  if ((typeof code === 'number' && code >= 500) || (typeof status === 'number' && status >= 500)) {
    return { kind: AiErrorKind.SERVER, message: raw }
  }
  if (code != null || status != null) {
    return { kind: AiErrorKind.INVALID, message: raw }
  }
  return { kind: AiErrorKind.UNKNOWN, message: raw }
}

/** 类目是否值得自动重试 */
export const isRetryable = (kind) =>
  kind === AiErrorKind.NETWORK ||
  kind === AiErrorKind.RATE_LIMITED ||
  kind === AiErrorKind.SERVER

/**
 * 默认文案 + 建议动作（「网络连接异常，请检查网络后重试」式拼接）。
 * 后端带回明确 message 时优先用后端的（业务语义更准），仅动作由类目给出。
 */
export const describeWithAction = ({ kind, message }) => {
  const def = KIND_TEXT[kind] || KIND_TEXT[AiErrorKind.UNKNOWN]
  const text = message || def.text
  return `${text}，${def.action}`
}

/**
 * 带指数退避的重试（2^n：1s → 2s，最多重试 2 次）。
 * 仅对可重试类目生效；UNAUTHORIZED 不重试（统一由调用方跳登录）。
 *
 * @param {() => Promise<T>} fn  执行体（无参，重试时重新调用）
 * @param {{onRetry?: (attempt: number, kind: string) => void}} [opts] onRetry 通知（可用于提示）
 * @returns {Promise<T>} 全部失败时抛出最后一次的原始异常
 */
export const withRetry = async (fn, opts = {}) => {
  let lastErr = null
  let lastKind = null
  for (let attempt = 0; attempt <= 2; attempt++) {
    try {
      return await fn()
    } catch (err) {
      lastErr = err
      const { kind } = classifyAiError(err)
      lastKind = kind
      if (!isRetryable(kind) || attempt === 2) break
      opts.onRetry?.(attempt + 1, kind)
      await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt))
    }
  }
  lastErr.kind = lastKind
  throw lastErr
}
