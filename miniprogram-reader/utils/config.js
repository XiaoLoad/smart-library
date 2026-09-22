/* ===== 环境配置 =====
 * dev：开发者工具勾选「不校验合法域名」可用 http 本地后端
 * prod：必须 HTTPS + 备案域名，并登记到小程序后台「服务器域名」
 */
const IS_DEV = true

const DEV_ORIGIN = 'http://10.180.61.179:8080'
const PROD_ORIGIN = 'https://api.your-library.example.com'

const ORIGIN = IS_DEV ? DEV_ORIGIN : PROD_ORIGIN

module.exports = {
  IS_DEV,
  ORIGIN,
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
