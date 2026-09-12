/* ===== axios 实例 + 拦截器 ===== */
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

// 响应拦截器：按后端 { code, message, data } 统一处理
service.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 200) {
      // 只回传 data 会让「仅提示、无数据」接口（Result.success(msg, null)）丢掉提示语，
      // 调用方取 res.message 会抛错，故此时补一个只带 message 的对象
      return data === null ? { message } : data
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
  },
  (error) => {
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
export const post = (url, data) => service.request({ method: 'post', url, data })
export default service
