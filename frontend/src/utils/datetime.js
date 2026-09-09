/**
 * 通用时间格式化工具
 *
 * 后端返回的时间是本地时区 ISO 字符串（如 2026-09-12T20:27:39.567074），
 * 直接字符串切片格式化，不走 Date 解析，避免无时区 ISO 被按 UTC 偏移解析的风险。
 */

/** 格式化为 YYYY-MM-DD HH:mm；空值返回 '—' */
export const formatDateTime = (t) => {
  if (!t) return '—'
  return String(t).replace('T', ' ').substring(0, 16)
}

/** 格式化为 YYYY-MM-DD；空值返回 '—' */
export const formatDate = (t) => {
  if (!t) return '—'
  return String(t).substring(0, 10)
}
