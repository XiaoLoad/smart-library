/* ===== Markdown → rich-text HTML（AI 对话气泡渲染） =====
 * 依赖 npm 包 marked（工具 → 构建 npm）；缺失时降级为纯文本
 *
 * 移动端表格策略：rich-text 对 <td>/<th> 的 border、padding 等内联样式支持不可靠（已知问题），
 * 原生 <table> 在窄气泡里线条、间距都丢，可读性差。故把 markdown 切成「片段数组」：
 * 普通片段用 rich-text 渲染；表格片段解析成结构化数据 {headers, rows}，由调用方用原生 WXML 视图
 * （flex 网格）+ 外层 scroll-view（scroll-x）渲染，边框/间距/隔行底色用真正 CSS 类控制，横滑查看。
 * Web 端走浏览器原生 table，不受影响。
 */
let marked = null
try {
  marked = require('marked')
} catch (e) {
  console.warn('[markdown] marked 未安装/未构建 npm，对话按纯文本渲染')
}

// rich-text 白名单外的标签/属性过滤兜底
function sanitize(html) {
  return String(html || '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/\son\w+="[^"]*"/gi, '')
}

function escapeHtml(text) {
  return String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>')
}

// 去掉标签、压缩空白，取纯文本（表格单元格用）
function stripTags(s) {
  return String(s || '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

// 提取一行 <tr> 内的所有单元格纯文本
function cellsOf(trHtml) {
  const m = trHtml.match(/<t[hd][^>]*>([\s\S]*?)<\/t[hd]>/gi) || []
  return m.map((c) => stripTags(c.replace(/<\/?t[hd][^>]*>/gi, '')))
}

// 把 <table> HTML 解析成结构化数据：{ headers:[...], rows:[[...],...] }
// 表格改用原生 WXML 视图渲染（rich-text 的 border/padding 不可靠），此处只取纯文本
function parseTable(tableHtml) {
  const rows = tableHtml.match(/<tr[\s\S]*?<\/tr>/gi) || []
  if (!rows.length) return null
  const headers = cellsOf(rows[0])
  const body = rows.slice(1).map(cellsOf)
  return { headers, rows: body }
}

/**
 * 把 markdown 切成渲染片段：
 *   {type:'rich', html}        普通块（rich-text 渲染）
 *   {type:'table', table}      表格块（结构化数据，原生 WXML flex 网格渲染 + 外层 scroll-view 横滑）
 * @returns {Array<{type:string, html?:string, table?:{headers:string[], rows:string[][]}}>}
 */
function toSegments(md) {
  if (!md) return []
  if (!marked) return [{ type: 'rich', html: escapeHtml(md) }]
  let html
  try {
    html = sanitize(marked.parse(md, { breaks: true, headerIds: false, mangle: false }))
  } catch (e) {
    return [{ type: 'rich', html: escapeHtml(md) }]
  }
  const segments = []
  const tableRe = /<table[\s\S]*?<\/table>/gi
  let last = 0
  let m
  while ((m = tableRe.exec(html)) !== null) {
    const before = html.slice(last, m.index)
    if (before.trim()) segments.push({ type: 'rich', html: before })
    const table = parseTable(m[0])
    if (table) segments.push({ type: 'table', table })
    last = m.index + m[0].length
  }
  const after = html.slice(last)
  if (after.trim()) segments.push({ type: 'rich', html: after })
  return segments.length ? segments : [{ type: 'rich', html }]
}

/**
 * @returns {string} 可直接赋给 <rich-text nodes> 的 HTML（无表格时与 toSegments 的 rich 片段一致）
 */
function toHtml(md) {
  if (!md) return ''
  if (!marked) return escapeHtml(md)
  try {
    return sanitize(marked.parse(md, { breaks: true, headerIds: false, mangle: false }))
  } catch (e) {
    return escapeHtml(md)
  }
}

module.exports = { toHtml, toSegments }
