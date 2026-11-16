/**
 * AI 对话 Markdown 渲染公共模块（/ai/chat 页与 AiChatPanel 悬浮球面板共用）。
 *
 * 渲染链路：markdown-it → HTML 字符串 → v-html。
 * 之前两处组件各自实例化 markdown-it + 一套重复样式，此模块统一收口：
 * - renderMarkdown(content, sources)   完整渲染（历史消息、done/final 终态）
 * - renderMarkdownStream(...)          流式渲染（delta 增量），先补齐未闭合语法再渲染
 * - handleCodeCopyClick(e)             代码块复制按钮的事件委托处理
 *
 * 样式见 styles/ai-markdown.css（全局样式——v-html 注入的内容无法命中 scoped 样式）。
 */
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import '../styles/ai-markdown.css'

const md = new MarkdownIt({
  html: false, // 不允许内嵌 HTML（防止 XSS）
  breaks: true, // 单换行转 <br>
  linkify: true, // 自动识别链接
})

// 代码块：语言标签栏 + 复制按钮（复制逻辑走事件委托，见 handleCodeCopyClick）
md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]
  const lang = (token.info || '').trim().split(/\s+/)[0] || ''
  let body = ''
  if (lang && hljs.getLanguage(lang)) {
    try {
      body = hljs.highlight(token.content, { language: lang, ignoreIllegals: true }).value
    } catch {
      // 高亮失败按纯文本输出
    }
  }
  if (!body) body = md.utils.escapeHtml(token.content)
  return (
    '<div class="md-code">' +
    '<div class="md-code-bar">' +
    `<span class="md-code-lang">${md.utils.escapeHtml(lang || 'text')}</span>` +
    '<button class="md-code-copy" type="button">复制</button>' +
    '</div>' +
    `<pre class="hljs"><code>${body}</code></pre>` +
    '</div>'
  )
}

// 链接新窗口打开，避免外链丢失对话页状态
const defaultLinkOpen =
  md.renderer.rules.link_open || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

/**
 * [资料 N] 引用角标渲染。
 * 把回答正文中的「[资料 N]」替换为内联角标（圆角小方块、主色、可点击），
 * 点击角标展开/收起正文上方参考资料条中对应的片段摘要（与药丸共用 ragOpenIndex）。
 * <p>
 * 实现约束：正文经 v-html 渲染，角标不能用 Vue 事件绑定——改为在角标上
 * 标 data-src 属性，由气泡容器的事件委托（handleCitationClick）统一处理。
 * 编号不存在（模型编造）时不渲染角标、原样保留文本，避免误导。
 *
 * @param {string} html  md.render 后的正文 HTML
 * @param {Array} sources 本轮 ragSources（[{sourceNo, docTitle, snippet}]）
 */
const CITE_RE = /\[资料\s*(\d+)\]/g
const renderCitations = (html, sources) => {
  if (!html || !sources || !sources.length) return html
  const valid = new Set(sources.map((s) => s.sourceNo))
  return html.replace(CITE_RE, (match, num) => {
    const n = Number(num)
    if (!valid.has(n)) return match
    return (
      `<sup class="cite-badge" data-src="${n}" title="查看资料 ${n}：${sources.find((s) => s.sourceNo === n)?.docTitle || ''}">` +
      `${n}</sup>`
    )
  })
}

/** 完整渲染（历史消息与终态：正文完整，无需补齐） */
export const renderMarkdown = (content, sources) => {
  if (!content) return ''
  try {
    return renderCitations(md.render(content), sources)
  } catch {
    return md.utils.escapeHtml(content)
  }
}

/**
 * 流式补齐：delta 增量渲染时正文随时可能截断在语法中间，未闭合的定界符
 * 会被 markdown-it 当普通字符显示（裸 **、裸 `、裸 ~~~ 围栏）。
 * 渲染前扫描定界符配对情况，给未闭合的补上闭合符；done/final 时用原始
 * 全文走 renderMarkdown 重渲染，落库内容不受影响。
 *
 * 只处理观感最刺眼的三类：代码围栏、行内代码反引号、** 加粗 / ~~ 删除线。
 * 单字符 * / _ 斜体不处理（乘号、下划线误判率高，且 markdown-it 对
 * 未闭合单字符的输出与闭合后观感差异小）。
 *
 * @param {string} src 流式累积的残缺 Markdown 文本
 */
export const completeStreamMarkdown = (src) => {
  if (!src) return src
  let out = src
  // 1) 代码围栏：``` / ~~~ 开栏行数为奇数 → 末尾补同字符闭合围栏
  const fences = out.match(/^[ \t]{0,3}(```+|~~~+)/gm)
  if (fences && fences.length % 2 === 1) {
    const mark = /(```+|~~~+)/.exec(fences[fences.length - 1])[1]
    out += '\n' + mark
  }
  // 2) 围栏外的文本做定界符配对检查（先剔除成对的围栏块与行内代码，避免误计）
  const text = out
    .replace(/```[\s\S]*?(```|$)/g, '')
    .replace(/~~~[\s\S]*?(~~~|$)/g, '')
    .replace(/(^|[^`])`[^`\n]+`(?![`])/g, '$1')
  // 行内代码：反引号连续段为奇数 → 补上最后一段等长的闭合反引号
  const runs = text.match(/`+/g)
  if (runs && runs.length % 2 === 1) out += runs[runs.length - 1]
  // 加粗 / 删除线：出现奇数次 → 补闭合
  if ((text.match(/\*\*/g) || []).length % 2 === 1) out += '**'
  if ((text.match(/~~/g) || []).length % 2 === 1) out += '~~'
  if ((text.match(/__/g) || []).length % 2 === 1) out += '__'
  return out
}

/** 流式渲染（delta 增量）：先补齐未闭合语法再走完整渲染 */
export const renderMarkdownStream = (content, sources) => renderMarkdown(completeStreamMarkdown(content), sources)

/**
 * 代码块复制按钮的事件委托处理（挂在助手气泡的 click 委托上，
 * 与角标 handleCitationClick 同一入口，先处理复制再走角标逻辑）。
 *
 * @returns {boolean} 命中复制按钮返回 true（调用方不再继续角标逻辑）
 */
export const handleCodeCopyClick = async (e) => {
  const btn = e.target.closest('.md-code-copy')
  if (!btn) return false
  const code = btn.closest('.md-code')?.querySelector('pre code')
  if (code && code.textContent) {
    try {
      await navigator.clipboard.writeText(code.textContent)
      btn.textContent = '已复制'
    } catch {
      btn.textContent = '复制失败'
    }
    setTimeout(() => {
      btn.textContent = '复制'
    }, 1500)
  }
  return true
}
