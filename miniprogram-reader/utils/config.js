/* ===== 环境配置 =====
 * dev：开发者工具勾选「不校验合法域名」可用 http 本地后端
 * prod：必须 HTTPS + 备案域名，并登记到小程序后台「服务器域名」
 */
const IS_DEV = true

const DEV_ORIGIN = 'http://192.168.1.146:8080'
const PROD_ORIGIN = 'https://api.your-library.example.com'

const ORIGIN = IS_DEV ? DEV_ORIGIN : PROD_ORIGIN

/* ===== 封面加载通道 =====
 * 真机上 <image> 加载 http 资源会被微信客户端拦截，开发者工具的「不校验合法域名」对图片不生效
 * （那个开关只管 wx.request 一类的 API 请求），因此接口数据能出来、封面却一片空白。
 * 四条通道：A 直连 <image> / B 背景图 / C base64 <image> / D 外链 https。
 *
 * 两条渲染路径，行为不同：
 *  · 书库网格：CSS background-image 直连 ORIGIN 图片（utils/cover.bgSrc，通道 B）。
 *    背景图由 WebView 渲染，不归小程序图片资源校验管，真机 http 也能显示；零数据量、无翻页上限。
 *    该路径与 COVER_MODE 无关，无论本值是什么都走直连背景图。
 *  · 书库详情封面 / 我的页头像：<image src> + utils/cover.coverSrc（通道 C）。
 *    <image> 仍受真机拦截，必须编成 data URI 才能显示，故这两处由 COVER_MODE 控制：
 *      'base64'：先给直连占位，再经 /file/image-base64 拉 data URI 回填（coverSrc 命中缓存即返回），真机可用；
 *      'direct' ：直接 <image src="http://…">，真机被拦（通道 A），仅开发者工具或「A 能出图」的真机正常。
 *
 * 外链 https 封面/头像不在本通道服务范围内，coverSrc 原样返回直连地址。
 * 想回退详情/头像到直连：改 'direct' 即可，书库网格不受影响。
 */
const COVER_MODE = 'base64'

module.exports = {
  IS_DEV,
  ORIGIN,
  COVER_MODE,
  BASE_URL: ORIGIN + '/api',
  // 上传静态资源前缀（后端 FileController 返回 /api/uploads/xxx）
  FILE_PREFIX: '/api/uploads/',
  // 把相对路径封面/头像解析为可加载的完整地址
  resolveUrl(path) {
    if (!path) return ''
    if (/^https?:\/\//.test(path)) return path
    return path.startsWith('/') ? ORIGIN + path : ORIGIN + '/' + path
  },
}
