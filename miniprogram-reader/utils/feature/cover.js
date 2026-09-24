/* ===== 封面加载通道 =====
 * 集中处理「同一个封面路径该以什么地址渲染」，让页面只认 coverSrc()。
 *
 * direct 模式：返回直连地址（原行为，一行不改）。
 * base64 模式：返回后端转好的 data URI；未命中缓存时先给直连地址占位，
 *              拉回来再回填，这样开发者工具里不会出现空窗。
 *
 * 只有站内相对路径（/api/uploads/...）才走 base64——外链封面本身是 https，
 * 不在本通道服务范围内，也无需转换。
 */
const { COVER_MODE, resolveUrl } = require('../core/config')
const request = require('../core/request')

const USE_BASE64 = COVER_MODE === 'base64'

/** 书库列表 3 列窄栏，最宽一列约 100px，3 倍屏 300px 已过采样；240 是体积与清晰度的折中 */
const THUMB_WIDTH = 240

/** 与后端 ImageBase64Service.MAX_BATCH 对齐 */
const BATCH = 24

/** 只留最近若干张，翻页很多时避免把编码后的字符串一直攥在内存里 */
const CACHE_MAX = 60

const cache = {}
const order = []

function remember(uri, dataUri) {
  if (!(uri in cache)) order.push(uri)
  cache[uri] = dataUri
  while (order.length > CACHE_MAX) delete cache[order.shift()]
}

/** 渲染用地址：空路径返回空串（模板据此走无封面占位） */
function coverSrc(uri) {
  if (!uri) return ''
  if (USE_BASE64 && cache[uri]) return cache[uri]
  return resolveUrl(uri)
}

/** 背景图渲染用地址：永远返回直连地址。背景图由 WebView 渲染，不归小程序图片资源校验管，
 *  真机上 http 也能显示（自检页 B 通道能出图即此理）。书库网格用它——零数据量、无翻页上限。 */
function bgSrc(uri) {
  return uri ? resolveUrl(uri) : ''
}

/** 直接向后端要编码结果，不写缓存。自检页用它对照体积，业务页面用 prefetch。 */
function fetchRaw(paths, width) {
  return request.get(
    '/file/image-base64',
    { paths: [].concat(paths).join(','), w: width },
    { silent: true },
  )
}

/**
 * 批量预取 base64。失败的条目原样跳过——封面取不到不该打断列表渲染。
 * @param {string[]} uris 后端返回的原始相对路径
 * @returns {Promise<boolean>} 是否新填充了缓存，调用方据此决定要不要重绘
 */
async function prefetch(uris) {
  if (!USE_BASE64) return false
  const todo = []
  ;(uris || []).forEach((u) => {
    // 外链（http/https 开头）不在服务范围内
    if (!u || u.charAt(0) !== '/' || cache[u]) return
    if (todo.indexOf(u) < 0) todo.push(u)
  })
  if (!todo.length) return false

  let filled = false
  for (let i = 0; i < todo.length; i += BATCH) {
    const chunk = todo.slice(i, i + BATCH)
    try {
      const res = await fetchRaw(chunk, THUMB_WIDTH)
      Object.keys(res || {}).forEach((uri) => {
        if (!res[uri]) return
        remember(uri, res[uri])
        filled = true
      })
    } catch (e) {
      /* 静默：列表/详情照常展示占位或直连地址 */
    }
  }
  return filled
}

/** 调试视图用：看看当前走哪条通道、缓存了多少张 */
function stat() {
  return { mode: COVER_MODE, useBase64: USE_BASE64, cached: order.length }
}

function clear() {
  order.length = 0
  Object.keys(cache).forEach((k) => delete cache[k])
}

module.exports = { USE_BASE64, THUMB_WIDTH, coverSrc, bgSrc, prefetch, fetchRaw, stat, clear }
