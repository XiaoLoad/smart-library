/* ===== 各模块 API 封装 ===== */
import { get, post } from './request.js'

// 认证
export const authApi = {
  login: (data) => post('/auth/login', data),
  getUserInfo: () => get('/user/info'),
  logout: () => Promise.resolve(),
}

// 用户
export const userApi = {
  list: (params) => get('/user/list', params),
  add: (data) => post('/user/add', data),
  update: (data) => post('/user/update', data),
  detail: (id) => get('/user/detail', { id }),
}

// 图书
export const bookApi = {
  list: (params) => get('/book/list', params),
  categoryTree: () => get('/book/category/tree'),
  add: (data) => post('/book/add', data),
  addCategory: (data) => post('/category/add', data),
  updateCategory: (data) => post('/category/update', data),
}

// 借阅
export const borrowApi = {
  list: (params) => get('/borrow/list', params),
  fineList: () => get('/fine/list'),
  apply: (data) => post('/borrow/apply', data),
  pickup: (data) => post('/borrow/pickup', data),
  return: (data) => post('/borrow/return', data),
  batchReturn: (data) => post('/borrow/batch-return', data),
  renew: (data) => post('/borrow/renew', data),
  quota: () => get('/borrow/quota'),
  payFine: (data) => post('/fine/pay', data),
}

// 公告
export const announcementApi = {
  list: (params) => get('/announcement/list', params),
  detail: (id) => get('/announcement/detail', { id }),
  add: (data) => post('/announcement/add', data),
  delete: (data) => post('/announcement/delete', data),
  toggleTop: (data) => post('/announcement/toggle-top', data),
}

// 通知
export const notificationApi = {
  list: () => get('/notification/list'),
}

// Dashboard
export const dashboardApi = {
  stats: (userType) => get('/dashboard/stats', { userType }),
}

// AI
export const aiApi = {
  conversationList: () => get('/ai/conversation/list'),
  messageList: (conversationId) => get('/ai/message/list', { conversationId }),
}

// 系统设置
export const systemApi = {
  changePassword: (data) => post('/system/change-password', data),
  getAiConfig: () => get('/system/ai-config'),
  saveAiConfig: (data) => post('/system/ai-config', data),
}
