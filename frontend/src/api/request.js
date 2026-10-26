/* axios 实例 + 拦截器 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { startProgress, doneProgress } from '@/utils/progress.js'

const service = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 请求拦截器：注入 token
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('library_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 登录态失效统一处理：清 token → 提示 → 跳登录页（携带 redirect 便于登录后回跳）
// 后端约定：JwtInterceptor 未授权时返回 HTTP 200 + body.code=401，故在成功分支拦截
let redirectingToLogin = false
function handleUnauthorized(message) {
  localStorage.removeItem('library_token')
  // 防重复：已在登录页或正在跳转时不再提示/跳转
  if (redirectingToLogin || window.location.pathname === '/login') {
    return Promise.reject(new Error(message))
  }
  redirectingToLogin = true
  ElMessage.error(message || '登录已过期，请重新登录')
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  setTimeout(() => {
    window.location.href = `/login?redirect=${redirect}`
  }, 500)
  return Promise.reject(new Error(message))
}

// 响应拦截器：按后端 { code, message, data } 统一处理
service.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 200) {
      // 只回传 data 会让「仅提示、无数据」接口（Result.success(msg, null)）丢掉提示语，
      // 调用方取 res.message 会抛错，故此时补一个只带 message 的对象
      return data === null ? { message } : data
    }
    if (code === 401) {
      return handleUnauthorized(message)
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
  },
  (error) => {
    // 兜底：若经代理/网关返回了真实 HTTP 401 也走同一处理
    if (error.response?.status === 401) {
      return handleUnauthorized('登录已过期，请重新登录')
    }
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

// 重写 request 方法以支持进度条
const originalRequest = service.request.bind(service)
service.request = async function (config) {
  startProgress()
  try {
    return await originalRequest(config)
  } finally {
    doneProgress()
  }
}

// 便捷方法
export const get = (url, params) => service.request({ method: 'get', url, params })
// config 透传，便于个别耗时接口（如批量催还）单独放宽超时
export const post = (url, data, config = {}) => service.request({ method: 'post', url, data, ...config })
export default service
