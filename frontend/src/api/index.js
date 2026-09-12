/* ===== 各模块 API 封装 ===== */
import { get, post, default as service } from './request.js'

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
  // 本人档案（自助）：后端恒取 token 里的用户，不接收入参
  profile: () => get('/user/profile'),
  updateProfile: (data) => post('/user/profile/update', data),
}

// 图书
export const bookApi = {
  list: (params) => get('/book/list', params),
  detail: (id) => get(`/book/${id}`),
  categoryTree: () => get('/book/category/tree'),
  add: (data) => post('/book/add', data),
  update: (data) => post('/book/update', data),
  addCategory: (data) => post('/category/add', data),
  updateCategory: (data) => post('/category/update', data),
  updateCategoryStatus: (data) => post('/category/status', data),
  deleteCategory: (data) => post('/category/delete', data),
}

// 文件上传（multipart）
export const uploadApi = {
  image: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return service.request({ method: 'post', url: '/upload/image', data: fd })
  },
}

// 借阅
export const borrowApi = {
  list: (params) => get('/borrow/list', params),
  fineList: () => get('/fine/list'),
  apply: (data) => post('/borrow/apply', data),
  pickup: (data) => post('/borrow/pickup', data),
  return: (data) => post('/borrow/return', data),
  confirmReturn: (data) => post('/borrow/return/confirm', data),
  rejectReturn: (data) => post('/borrow/return/reject', data),
  batchProcess: (data) => post('/borrow/batch-process', data),
  urge: (data) => post('/borrow/urge', data),
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
  unreadCount: () => get('/notification/unread-count'),
  read: (id) => post('/notification/read', { id }),
  readAll: () => post('/notification/read-all'),
}

// Dashboard
export const dashboardApi = {
  stats: (userType) => get('/dashboard/stats', { userType }),
}

// AI
// 注：流式对话 SSE 不走 axios（不支持流式解析），在 chat.vue 内用 fetch+ReadableStream 实现
export const aiApi = {
  conversationList: () => get('/ai/conversation/list'),
  messageList: (conversationId, params) => get('/ai/message/list', { conversationId, ...(params || {}) }),
  createConversation: () => post('/ai/conversation/create'),
  deleteConversation: (id) => service.delete(`/ai/conversation/${id}`),
}

// 系统设置
export const systemApi = {
  changePassword: (data) => post('/system/change-password', data),
  // AI 渠道（多渠道管理）
  getChannels: () => get('/system/ai-channels'),
  addChannel: (data) => post('/system/ai-channels', data),
  updateChannel: (data) => service.put('/system/ai-channels', data),
  toggleChannel: (data) => post('/system/ai-channels/toggle', data),
  moveChannel: (data) => post('/system/ai-channels/move', data),
  deleteChannel: (data) => post('/system/ai-channels/delete', data),
  // 测试连通性并拉取模型列表（传 id 测已保存渠道，或传 provider/apiKey/endpoint 测表单）
  testChannel: (data) => post('/system/ai-channels/test', data),
  // 借阅规则
  getBorrowLimits: () => get('/system/borrow-limits'),
  saveBorrowLimit: (data) => post('/system/borrow-limits', data),
  getLibraryRules: () => get('/system/library-rules'),
  saveLibraryRules: (data) => post('/system/library-rules', data),
  // AI 人设（可调整 Prompt 段）
  getAiPrompt: () => get('/system/ai-prompt'),
  saveAiPrompt: (data) => service.put('/system/ai-prompt', data),
  // 催还通知
  getNotificationRules: () => get('/system/notification-rules'),
  saveNotificationRules: (rules) => post('/system/notification-rules', rules),
  getMailConfig: () => get('/system/mail-config'),
  saveMailConfig: (data) => post('/system/mail-config', data),
  getNotificationLogs: (params) => get('/system/notifications', params),
  resendNotification: (id) => post('/system/notifications/resend', { id }),
  triggerNotification: () => post('/system/notifications/trigger'),
}
