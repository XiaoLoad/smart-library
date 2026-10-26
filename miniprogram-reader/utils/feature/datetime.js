/* 日期格式化（后端 LocalDateTime 序列化为 ISO 字符串） */
function fmtDateTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').substring(0, 16)
}

function fmtDate(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').substring(0, 10)
}

function fmtMoney(v) {
  const n = Number(v || 0)
  return '¥' + (Math.round(n * 100) / 100).toFixed(2)
}

module.exports = { fmtDateTime, fmtDate, fmtMoney }
