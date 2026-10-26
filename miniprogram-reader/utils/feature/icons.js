/* 日式极简线性图标库（内联 SVG · 无 emoji）
 * 描边风格：24 网格 / 1.6 线宽 / 圆端点，与 Web 端克制美学一致
 * 用法：const { getIcon } = require('./icons'); getIcon('search', '#9c958d')
 */
const TABLE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function b64(str) {
  let out = ''
  for (let i = 0; i < str.length; i += 3) {
    const c1 = str.charCodeAt(i)
    const c2 = str.charCodeAt(i + 1)
    const c3 = str.charCodeAt(i + 2)
    out += TABLE[c1 >> 2]
    out += TABLE[((c1 & 3) << 4) | ((isNaN(c2) ? 0 : c2) >> 4)]
    out += isNaN(c2) ? '=' : TABLE[((c2 & 15) << 2) | ((isNaN(c3) ? 0 : c3) >> 6)]
    out += isNaN(c3) ? '=' : TABLE[c3 & 63]
  }
  return out
}

/* 图标字典：name -> (color) => svg 内部元素 */
const ICONS = {
  search: (c) => `<circle cx="11" cy="11" r="7"/><path d="M16.5 16.5 21 21"/>`,
  'chevron-down': () => `<path d="M6 9.5 12 15.5 18 9.5"/>`,
  'chevron-right': () => `<path d="M9.5 6 15.5 12 9.5 18"/>`,
  close: () => `<path d="M6 6 18 18M18 6 6 18"/>`,
  check: () => `<path d="M5 12.5 10 17.5 19 7"/>`,
  plus: () => `<path d="M12 5v14M5 12h14"/>`,
  menu: () => `<path d="M4 7h16M4 12h16M4 17h10"/>`,
  book: () => `<path d="M6 3h11a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M4 7h3M4 12h3M4 17h3"/>`,
  'book-open': () => `<path d="M12 6.5C10 4.5 7 4 4 4.5v13c3-.5 6 0 8 2 2-2 5-2.5 8-2v-13c-3-.5-6 0-8 2.5z"/><path d="M12 6.5v13"/>`,
  scroll: () => `<path d="M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/><path d="M8 8h8M8 12h8M8 16h5"/>`,
  bell: () => `<path d="M6 9a6 6 0 0 1 12 0c0 4.5 1.8 5.5 1.8 5.5H4.2S6 13.5 6 9z"/><path d="M10 18.5a2 2 0 0 0 4 0"/>`,
  user: () => `<circle cx="12" cy="8" r="3.6"/><path d="M4.5 20c1.3-3.7 4.2-5.6 7.5-5.6s6.2 1.9 7.5 5.6"/>`,
  wechat: (c) =>
    `<path d="M21 9.6c0-3-2.9-5.4-6.4-5.4S8.2 6.6 8.2 9.6c0 3 2.9 5.4 6.4 5.4.5 0 1-.05 1.5-.14L18.4 16.5l-.8-2.1c2-.9 3.4-2.7 3.4-4.8z"/><path d="M5 10c.3-2 1.8-3.7 4-4.5" opacity=".5"/><circle cx="12.2" cy="8.7" r=".9" fill="${c}" stroke="none"/><circle cx="16.4" cy="8.7" r=".9" fill="${c}" stroke="none"/>`,
  lock: () => `<rect x="5" y="10.5" width="14" height="9.5" rx="1.5"/><path d="M8.2 10.5V7.8a3.8 3.8 0 0 1 7.6 0v2.7"/>`,
  'id-card': () => `<rect x="3" y="5" width="18" height="14" rx="1.5"/><circle cx="8.5" cy="10.8" r="2"/><path d="M5.5 16.2c.6-1.6 1.7-2.4 3-2.4s2.4.8 3 2.4M14 9.5h4.5M14 13.5h4.5"/>`,
  coin: (c) =>
    `<circle cx="12" cy="12" r="8"/><path d="M8.8 8.5 12 12.2l3.2-3.7M12 12.2v3.6M9.5 13.2h5M9.5 15.2h5"/>`,
  chart: () => `<path d="M4.5 20V11M10 20V5M15.5 20v-6M21 20V8.5"/>`,
  sparkle: () => `<path d="M12 3.5 13.9 8.9 19.3 10.8 13.9 12.7 12 18.1 10.1 12.7 4.7 10.8 10.1 8.9z"/><path d="M18.5 4v3M20 5.5h-3"/>`,
  clock: () => `<circle cx="12" cy="12" r="8"/><path d="M12 7.5V12l3.2 2"/>`,
  'check-circle': () => `<circle cx="12" cy="12" r="8"/><path d="M8.2 12.2 11 15l5.2-5.6"/>`,
  'x-circle': () => `<circle cx="12" cy="12" r="8"/><path d="M9.2 9.2 14.8 14.8M14.8 9.2 9.2 14.8"/>`,
  'arrow-up': () => `<path d="M12 19V5M6 11l6-6 6 6"/>`,
  seal: (c) =>
    `<rect x="4.5" y="4.5" width="15" height="15" rx="1.5" stroke-width="1.2"/><path d="M8 9h8M8 12h8M8 15h8" opacity=".35"/>`,
}

function getIcon(name, color) {
  const c = color || '#6b6560'
  const gen = ICONS[name] || ICONS.book
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="' +
    c +
    '" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">' +
    gen(c) +
    '</svg>'
  return 'data:image/svg+xml;base64,' + b64(svg)
}

module.exports = { getIcon }
