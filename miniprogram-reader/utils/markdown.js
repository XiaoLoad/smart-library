/* ===== Markdown → rich-text HTML（AI 对话气泡渲染） =====
 * 依赖 npm 包 marked（工具 → 构建 npm）；缺失时降级为纯文本
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

/**
 * @returns {string} 可直接赋给 <rich-text nodes> 的 HTML
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

module.exports = { toHtml }
