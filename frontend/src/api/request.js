/* ===== axios 实例 + 拦截器 + mock 适配 ===== */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { startProgress, doneProgress } from '@/utils/progress.js'
import {
  mockUsers,
  mockBooks,
  mockBorrows,
  mockFines,
  mockAnnouncements,
  mockNotifications,
  mockDashboard,
  mockDashboardTeacher,
  mockDashboardStudent,
  mockCategories,
  mockAIConversations,
  mockAIMessages,
  mockLogin,
  mockBorrowLimits,
} from './mock.js'

// ===== mock 工具函数 =====
const parseUserId = (config) => {
  const auth = config.headers?.Authorization || config.headers?.authorization || ''
  const token = auth.replace(/^Bearer\s+/i, '')
  const m = token.match(/^mock-token-(\d+)$/)
  return m ? Number(m[1]) : null
}

const nowStr = () => {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const addDays = (dateStr, days) => {
  if (!dateStr) return null
  const d = new Date(dateStr.replace(/-/g, '/'))
  d.setDate(d.getDate() + days)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

let borrowSeq = 7
const genBorrowNo = () => {
  borrowSeq++
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const rand = Math.random().toString(36).slice(2, 6).toUpperCase()
  return `BR${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${String(borrowSeq).padStart(4, '0')}${rand}`
}

// 自动同步借阅状态：待取书超期取消 + 借阅中逾期判定
const syncBorrowStatus = () => {
  const now = new Date()
  mockBorrows.forEach((b) => {
    // 待取书超期 → 已取消（status=4），恢复库存
    if (b.status === 0 && b.pickupDeadline) {
      const deadline = new Date(b.pickupDeadline.replace(/-/g, '/'))
      if (deadline < now) {
        b.status = 4
        b.remark = '取书超期已自动取消'
        const book = mockBooks.find((bk) => bk.id === b.bookId)
        if (book) book.availableCount++
      }
    }
    // 借阅中逾期 → 已逾期（status=3）
    if (b.status === 1 && b.dueTime) {
      const due = new Date(b.dueTime.replace(/-/g, '/'))
      if (due < now) {
        b.status = 3
        if (!b.remark) b.remark = '已超期'
      }
    }
  })
}

// ===== mock 路由表 =====
// 通过拦截 request 匹配 url + method 返回 mock 数据
const mockRoutes = [
  { url: '/auth/login', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const { username, password } = body
    const result = mockLogin(username, password)
    if (!result) return { code: 401, message: '用户名或密码错误' }
    return { code: 200, data: result, message: '登录成功' }
  }},
  { url: '/user/info', method: 'get', handler: () => {
    const u = mockUsers[3] // 默认演示学生身份
    return { code: 200, data: { user: u } }
  }},
  { url: '/user/list', method: 'get', handler: (config) => {
    const { page = 1, size = 10, keyword = '' } = config.params || {}
    let list = mockUsers
    if (keyword) {
      list = mockUsers.filter((u) =>
        u.username.includes(keyword) || u.realName.includes(keyword)
      )
    }
    const total = list.length
    const data = list.slice((page - 1) * size, page * size)
    return { code: 200, data: { list: data, total } }
  }},
  { url: '/book/list', method: 'get', handler: (config) => {
    const { page = 1, size = 10, keyword = '', categoryId } = config.params || {}
    let list = mockBooks
    if (keyword) {
      list = mockBooks.filter((b) =>
        b.title.includes(keyword) || b.author.includes(keyword) || b.isbn.includes(keyword)
      )
    }
    if (categoryId) {
      // 收集该节点及所有子孙节点 id，支持点击上级分类
      const collectIds = (nodes, targetId) => {
        for (const n of nodes) {
          if (n.id === targetId) return n
          if (n.children) {
            const found = collectIds(n.children, targetId)
            if (found) return found
          }
        }
        return null
      }
      const collectDescendantIds = (node) => {
        const ids = [node.id]
        if (node.children) node.children.forEach((c) => ids.push(...collectDescendantIds(c)))
        return ids
      }
      const target = collectIds(mockCategories, Number(categoryId))
      const ids = target ? collectDescendantIds(target) : [Number(categoryId)]
      list = list.filter((b) => ids.includes(b.categoryId))
    }
    const total = list.length
    const data = list.slice((page - 1) * size, page * size)
    return { code: 200, data: { list: data, total } }
  }},
  { url: '/book/category/tree', method: 'get', handler: () => {
    return { code: 200, data: mockCategories }
  }},
  { url: '/borrow/list', method: 'get', handler: (config) => {
    syncBorrowStatus()
    const { page = 1, size = 10, status, search = '' } = config.params || {}
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    // 非管理员仅返回本人记录
    let list = user && user.userType !== 3
      ? mockBorrows.filter((b) => b.userId === userId)
      : mockBorrows
    // 按借阅人/单号模糊搜索
    if (search) {
      list = list.filter((b) => b.userName.includes(search) || b.borrowNo.includes(search))
    }
    if (status !== undefined && status !== '') {
      list = list.filter((b) => b.status === Number(status))
    }
    const total = list.length
    const data = list.slice((page - 1) * size, page * size)
    return { code: 200, data: { list: data, total } }
  }},
  { url: '/borrow/apply', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const { bookId } = body
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user) return { code: 403, message: '用户未登录' }
    const limit = mockBorrowLimits[user.userType]
    if (!limit) return { code: 403, message: '管理员账号不可借阅' }
    // 校验额度（借阅中+待取书）
    const used = mockBorrows.filter((b) => b.userId === userId && (b.status === 0 || b.status === 1)).length
    if (used >= limit.maxBooks) return { code: 400, message: `借阅额度已满（${limit.maxBooks}本），请先归还` }
    // 校验逾期
    const hasOverdue = mockBorrows.some((b) => b.userId === userId && b.status === 3)
    if (hasOverdue) return { code: 400, message: '您有逾期未还图书，请先归还' }
    // 校验库存
    const book = mockBooks.find((b) => b.id === Number(bookId))
    if (!book) return { code: 400, message: '图书不存在' }
    if (book.availableCount <= 0) return { code: 400, message: '该图书暂无库存' }
    // 生成借阅记录
    const now = nowStr()
    const borrowNo = genBorrowNo()
    const record = {
      id: Date.now(),
      borrowNo,
      userId,
      userName: user.realName,
      bookId: book.id,
      bookTitle: book.title,
      applyTime: now,
      borrowTime: null,
      dueTime: null,
      returnTime: null,
      renewCount: 0,
      renewMaxCount: limit.renewMaxCount,
      pickupDeadline: addDays(now, limit.pickupDays),
      status: 0,
      remark: '待取书',
      qrPayload: JSON.stringify({ borrowNo, userId, bookId: book.id, bookTitle: book.title }),
    }
    mockBorrows.push(record)
    book.availableCount--
    return { code: 200, data: record, message: '借阅申请成功' }
  }},
  { url: '/borrow/pickup', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可取书确认' }
    // 支持单个 borrowNo 或批量 borrowNos
    const borrowNos = Array.isArray(body.borrowNos) ? body.borrowNos : (body.borrowNo ? [body.borrowNo] : [])
    if (borrowNos.length === 0) return { code: 400, message: '请提供借阅码' }
    const results = []
    for (const no of borrowNos) {
      const record = mockBorrows.find((b) => b.borrowNo === no)
      if (!record) { results.push({ borrowNo: no, success: false, message: '借阅码不存在' }); continue }
      if (record.status !== 0) { results.push({ borrowNo: no, success: false, message: '该记录非待取书状态' }); continue }
      // 直接根据借阅人的 userType 获取额度
      const borrower = mockUsers.find((u) => u.id === record.userId)
      const limit = mockBorrowLimits[borrower?.userType]
      if (!limit) { results.push({ borrowNo: no, success: false, message: '无法获取借阅额度' }); continue }
      const now = nowStr()
      record.status = 1
      record.borrowTime = now
      record.dueTime = addDays(now, limit.loanDays)
      record.remark = ''
      results.push({ borrowNo: no, success: true, record })
    }
    const successCount = results.filter((r) => r.success).length
    const failCount = results.length - successCount
    return { code: 200, data: { results, successCount, failCount }, message: successCount === results.length ? '取书成功' : `成功 ${successCount} 条，失败 ${failCount} 条` }
  }},
  { url: '/borrow/return', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user) return { code: 403, message: '用户未登录' }
    const record = mockBorrows.find((b) => b.id === body.id || b.borrowNo === body.borrowNo)
    if (!record) return { code: 400, message: '借阅记录不存在' }
    // 非管理员只能归还本人记录
    if (user.userType !== 3 && record.userId !== userId) return { code: 403, message: '只能归还本人借阅记录' }
    if (record.status !== 1 && record.status !== 3) return { code: 400, message: '仅借阅中或已逾期可归还' }
    record.status = 2
    record.returnTime = nowStr()
    const book = mockBooks.find((b) => b.id === record.bookId)
    if (book) book.availableCount++
    return { code: 200, data: record, message: '归还成功' }
  }},
  { url: '/borrow/renew', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const record = mockBorrows.find((b) => b.id === body.id || b.borrowNo === body.borrowNo)
    if (!record) return { code: 400, message: '借阅记录不存在' }
    if (record.status !== 1) return { code: 400, message: '仅借阅中可续借' }
    if (record.renewCount >= record.renewMaxCount) return { code: 400, message: '续借次数已达上限' }
    // 有其他逾期记录不可续借
    const hasOverdue = mockBorrows.some((b) => b.userId === record.userId && b.status === 3 && b.id !== record.id)
    if (hasOverdue) return { code: 400, message: '您有其他逾期未还图书，请先归还' }
    record.renewCount++
    const user = mockUsers.find((u) => u.id === record.userId)
    const limit = mockBorrowLimits[user?.userType]
    if (limit) record.dueTime = addDays(record.dueTime, limit.renewDays)
    return { code: 200, data: record, message: '续借成功' }
  }},
  { url: '/borrow/quota', method: 'get', handler: (config) => {
    syncBorrowStatus()
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user) return { code: 403, message: '用户未登录' }
    const limit = mockBorrowLimits[user.userType]
    if (!limit) return { code: 200, data: { maxBooks: 0, used: 0, remaining: 0, overdueCount: 0, canBorrow: false } }
    const used = mockBorrows.filter((b) => b.userId === userId && (b.status === 0 || b.status === 1)).length
    const overdueCount = mockBorrows.filter((b) => b.userId === userId && b.status === 3).length
    return { code: 200, data: {
      maxBooks: limit.maxBooks,
      loanDays: limit.loanDays,
      renewMaxCount: limit.renewMaxCount,
      renewDays: limit.renewDays,
      used,
      remaining: limit.maxBooks - used,
      overdueCount,
      canBorrow: used < limit.maxBooks && overdueCount === 0,
    } }
  }},
  { url: '/fine/list', method: 'get', handler: () => {
    // 返回浅拷贝避免 Vue 引用比对导致不刷新
    const list = mockFines.map((f) => ({ ...f }))
    return { code: 200, data: { list, total: list.length } }
  }},
  { url: '/fine/pay', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user) return { code: 403, message: '用户未登录' }
    const fine = mockFines.find((f) => f.id === Number(body.id))
    if (!fine) return { code: 400, message: '罚款记录不存在' }
    if (fine.status === 1) return { code: 400, message: '该罚款已缴纳' }
    // 非管理员只能缴本人罚款
    if (user.userType !== 3 && fine.userId !== userId) return { code: 403, message: '只能缴纳本人罚款' }
    fine.status = 1
    fine.paidTime = nowStr()
    return { code: 200, data: fine, message: '缴费成功' }
  }},
  { url: '/book/add', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可新增图书' }
    // 校验必填
    if (!body.title || !body.author || !body.isbn || !body.categoryId) {
      return { code: 400, message: '书名、作者、ISBN、分类为必填项' }
    }
    // ISBN 唯一性
    if (mockBooks.some((b) => b.isbn === body.isbn)) {
      return { code: 400, message: '该 ISBN 已存在' }
    }
    const newBook = {
      id: mockBooks.length ? Math.max(...mockBooks.map((b) => b.id)) + 1 : 1,
      title: body.title,
      author: body.author,
      publisher: body.publisher || '',
      isbn: body.isbn,
      categoryId: Number(body.categoryId),
      location: body.location || '',
      price: Number(body.price) || 0,
      publishDate: body.publishDate || '',
      coverUrl: body.coverUrl || '',
      description: body.description || '',
      totalCount: Number(body.totalCount) || 1,
      availableCount: Number(body.totalCount) || 1,
      status: 1,
      createTime: nowStr(),
    }
    mockBooks.push(newBook)
    return { code: 200, data: newBook, message: '新增图书成功' }
  }},
  { url: '/book/update', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可编辑图书' }
    const book = mockBooks.find((b) => b.id === Number(body.id))
    if (!book) return { code: 400, message: '图书不存在' }
    if (mockBooks.some((b) => b.isbn === body.isbn && b.id !== book.id)) {
      return { code: 400, message: '该 ISBN 已存在' }
    }
    const delta = (Number(body.totalCount) || 1) - book.totalCount
    book.title = body.title
    book.author = body.author
    book.publisher = body.publisher || ''
    book.isbn = body.isbn
    book.categoryId = Number(body.categoryId)
    book.location = body.location || ''
    book.price = Number(body.price) || 0
    book.coverUrl = body.coverUrl || ''
    book.description = body.description || ''
    book.totalCount += delta
    book.availableCount = Math.max(0, Math.min(book.availableCount + delta, book.totalCount))
    return { code: 200, data: book, message: '修改成功' }
  }},
  { url: '/category/add', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可新增分类' }
    if (!body.categoryName || !body.categoryCode) {
      return { code: 400, message: '分类名称、编码为必填项' }
    }
    // 编码唯一性
    const codeExists = (nodes) => nodes.some((n) => n.categoryCode === body.categoryCode || (n.children && codeExists(n.children)))
    if (codeExists(mockCategories)) return { code: 400, message: '该分类编码已存在' }
    const parentId = Number(body.parentId) || 0
    const newCategory = {
      id: Date.now(),
      parentId,
      categoryName: body.categoryName,
      categoryCode: body.categoryCode,
      level: parentId === 0 ? 1 : 2,
      sortOrder: Number(body.sortOrder) || 1,
      status: 1,
    }
    if (parentId === 0) {
      // 根分类
      newCategory.children = []
      mockCategories.push(newCategory)
    } else {
      // 子分类：挂到对应父节点
      const findParent = (nodes) => {
        for (const n of nodes) {
          if (n.id === parentId) return n
          if (n.children) {
            const found = findParent(n.children)
            if (found) return found
          }
        }
        return null
      }
      const parent = findParent(mockCategories)
      if (!parent) return { code: 400, message: '父分类不存在' }
      newCategory.level = parent.level + 1
      if (!parent.children) parent.children = []
      parent.children.push(newCategory)
    }
    return { code: 200, data: newCategory, message: '新增分类成功' }
  }},
  { url: '/announcement/list', method: 'get', handler: (config) => {
    const { page = 1, size = 10 } = config.params || {}
    // 置顶优先 + 时间倒序
    const sorted = [...mockAnnouncements].sort((a, b) => {
      if (a.top !== b.top) return b.top - a.top
      return new Date(b.publishTime) - new Date(a.publishTime)
    })
    const total = sorted.length
    const data = sorted.slice((page - 1) * size, page * size)
    return { code: 200, data: { list: data, total } }
  }},
  { url: '/announcement/detail', method: 'get', handler: (config) => {
    const { id } = config.params || {}
    const item = mockAnnouncements.find((a) => a.id === Number(id))
    if (!item) return { code: 400, message: '公告不存在' }
    return { code: 200, data: { ...item } }
  }},
  { url: '/announcement/add', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可发布公告' }
    if (!body.title || !body.content) return { code: 400, message: '标题、内容为必填项' }
    const newAnnounce = {
      id: mockAnnouncements.length ? Math.max(...mockAnnouncements.map((a) => a.id)) + 1 : 1,
      title: body.title,
      content: body.content,
      publisherId: userId,
      publisherName: user.realName,
      top: body.top ? 1 : 0,
      status: 1,
      publishTime: nowStr(),
    }
    mockAnnouncements.unshift(newAnnounce)
    return { code: 200, data: newAnnounce, message: '发布成功' }
  }},
  { url: '/announcement/delete', method: 'post', handler: (config) => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : (config.data || {})
    const userId = parseUserId(config)
    const user = mockUsers.find((u) => u.id === userId)
    if (!user || user.userType !== 3) return { code: 403, message: '仅管理员可删除公告' }
    const idx = mockAnnouncements.findIndex((a) => a.id === Number(body.id))
    if (idx === -1) return { code: 400, message: '公告不存在' }
    mockAnnouncements.splice(idx, 1)
    return { code: 200, message: '删除成功' }
  }},
  { url: '/notification/list', method: 'get', handler: () => {
    return { code: 200, data: { list: mockNotifications, total: mockNotifications.length } }
  }},
  { url: '/dashboard/stats', method: 'get', handler: (config) => {
    const { userType } = config.params || {}
    // 1=教师 2=学生 3=管理员
    const data = Number(userType) === 1 ? mockDashboardTeacher
      : Number(userType) === 2 ? mockDashboardStudent
      : mockDashboard
    return { code: 200, data }
  }},
  { url: '/ai/conversation/list', method: 'get', handler: () => {
    return { code: 200, data: { list: mockAIConversations, total: mockAIConversations.length } }
  }},
  { url: '/ai/message/list', method: 'get', handler: (config) => {
    const { conversationId } = config.params || {}
    const list = mockAIMessages.filter((m) => m.conversationId === Number(conversationId))
    return { code: 200, data: { list, total: list.length } }
  }},
]

// 模拟网络延迟
const delay = (ms = 200) => new Promise((resolve) => setTimeout(resolve, ms))

const service = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 请求拦截器：注入 token + mock 适配
service.interceptors.request.use(
  async (config) => {
    const token = localStorage.getItem('library_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 200) {
      return data
    }
    ElMessage.error(message || '请求失败')
    return Promise.reject(new Error(message))
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

// 重写 request 方法以支持进度条（mock 已停用，走真实后端）
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
