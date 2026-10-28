/* 各模块 API 封装 */
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
  // 催还含邮件下发，单条/批量都可能较久，单独放宽超时避免 10s 悬崖
  urge: (data) => post('/borrow/urge', data, { timeout: 30000 }),
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
  // 确认执行写操作：回传令牌与工具名，参数由服务端保存，客户端无法篡改；
  // conversationId 供服务端二次校验会话归属（不传时服务端跳过该校验）；
  // alwaysAllow=true 同时勾选「本会话始终允许该操作」，确认成功后同类写操作不再弹卡
  confirmWrite: (token, tool, conversationId, alwaysAllow) =>
    post('/ai/confirm', { token, tool, conversationId, alwaysAllow }),
  // 批量授权确认（batch 档位）：只回传批次令牌，服务端按保存的批次顺序逐项执行
  confirmBatchWrite: (token, conversationId) => post('/ai/confirm/batch', { token, conversationId }),
  // M9 授权卡决策回执：把已成事实的决策态（confirmed/cancelled/expired/failed）
  // 存入消息快照通道，历史消息据此重建只读授权卡。展示层记录，不执行写操作
  recordCardDecision: (payload) => post('/ai/card-decision', payload),
  // 设置会话写操作权限档位（confirm=逐笔确认 / batch=批量授权 / auto=自动执行，auto 仅管理员）
  setPermissionMode: (id, mode) => post(`/ai/conversation/${id}/permission-mode?mode=${encodeURIComponent(mode)}`),
}

// M6a：Agent 运行轨迹（管理员）
export const traceApi = {
  list: (params) => get('/ai/trace/list', params),
  detail: (id) => get(`/ai/trace/${id}`),
  summary: () => get('/ai/trace/summary'),
}

// 知识库（RAG，仅管理员）
export const ragApi = {
  documents: (params) => get('/rag/documents', params),
  // 上传 txt/md：只解析文本入库，不保留原文件；大文件解析较久，放宽超时
  upload: (file, { title, scope } = {}) => {
    const fd = new FormData()
    fd.append('file', file)
    if (title) fd.append('title', title)
    if (scope) fd.append('scope', scope)
    return service.request({ method: 'post', url: '/rag/documents/upload', data: fd, timeout: 60000 })
  },
  createByText: (data) => post('/rag/documents/text', data),
  updateStatus: (id, status) => service.put(`/rag/documents/${id}/status`, null, { params: { status } }),
  deleteDocument: (id) => service.delete(`/rag/documents/${id}`),
  // 重建含逐片向量化，单篇也可能上百片，放宽超时
  reindex: (id) => post(`/rag/documents/${id}/reindex`, {}, { timeout: 120000 }),
  rebuildAll: () => post('/rag/rebuild', {}, { timeout: 300000 }),
  segments: (id) => get(`/rag/documents/${id}/segments`),
  searchTest: (params) => post('/rag/search-test', null, { params }),
  getConfig: () => get('/rag/config'),
  saveConfig: (data) => post('/rag/config', data),
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
  // 重发含邮件下发，单独放宽超时避免 10s 悬崖
  resendNotification: (id) => post('/system/notifications/resend', { id }, { timeout: 30000 }),
  // 手动触发扫描可能批量发多封邮件，给更长超时
  triggerNotification: () => post('/system/notifications/trigger', {}, { timeout: 60000 }),
}
