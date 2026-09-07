/* ===== Mock 数据 · 智慧图书管理系统 ===== */
/* 后端未接入前的临时数据，模拟 API 响应结构 */

// 用户
export const mockUsers = [
  { id: 1, username: 'admin', realName: '系统管理员', userType: 3, roleName: '超级管理员', email: 'admin@library.edu', phone: '13800138000', status: 1, createTime: '2026-09-01 09:00:00', avatar: '' },
  { id: 2, username: 'teacher01', realName: '李文清', userType: 1, roleName: '教师', email: 'li.wq@library.edu', phone: '13900139001', status: 1, createTime: '2026-09-02 10:30:00', teacherNo: 'T2026001', department: '中文系', title: '副教授' },
  { id: 3, username: 'teacher02', realName: '陈书韵', userType: 1, roleName: '教师', email: 'chen.sy@library.edu', phone: '13900139002', status: 1, createTime: '2026-09-02 11:15:00', teacherNo: 'T2026002', department: '历史系', title: '讲师' },
  { id: 4, username: 'student01', realName: '林知微', userType: 2, roleName: '学生', email: 'lin.zw@stu.library.edu', phone: '13700137001', status: 1, createTime: '2026-09-03 14:20:00', studentNo: 'S20260001', department: '中文系', className: '汉语言1班', grade: '2026', major: '汉语言文学' },
  { id: 5, username: 'student02', realName: '苏念安', userType: 2, roleName: '学生', email: 'su.na@stu.library.edu', phone: '13700137002', status: 1, createTime: '2026-09-03 15:00:00', studentNo: 'S20260002', department: '历史系', className: '历史学1班', grade: '2026', major: '中国史' },
  { id: 6, username: 'student03', realName: '顾沉舟', userType: 2, roleName: '学生', email: 'gu.cz@stu.library.edu', phone: '13700137003', status: 0, createTime: '2026-09-04 09:10:00', studentNo: 'S20260003', department: '哲学系', className: '哲学1班', grade: '2026', major: '哲学' },
]

// 图书分类树
export const mockCategories = [
  { id: 1, parentId: 0, categoryName: '文学', categoryCode: 'LIT', level: 1, sortOrder: 1, status: 1, children: [
    { id: 11, parentId: 1, categoryName: '中国文学', categoryCode: 'LIT-CN', level: 2, sortOrder: 1, status: 1, children: [
      { id: 111, parentId: 11, categoryName: '古典文学', categoryCode: 'LIT-CN-CLS', level: 3, sortOrder: 1, status: 1 },
      { id: 112, parentId: 11, categoryName: '当代文学', categoryCode: 'LIT-CN-MOD', level: 3, sortOrder: 2, status: 1 },
    ]},
    { id: 12, parentId: 1, categoryName: '外国文学', categoryCode: 'LIT-FG', level: 2, sortOrder: 2, status: 1 },
  ]},
  { id: 2, parentId: 0, categoryName: '历史·哲学', categoryCode: 'HIS', level: 1, sortOrder: 2, status: 1, children: [
    { id: 21, parentId: 2, categoryName: '中国历史', categoryCode: 'HIS-CN', level: 2, sortOrder: 1, status: 1 },
    { id: 22, parentId: 2, categoryName: '西方哲学', categoryCode: 'HIS-PHI', level: 2, sortOrder: 2, status: 1 },
  ]},
  { id: 3, parentId: 0, categoryName: '科学·技术', categoryCode: 'SCI', level: 1, sortOrder: 3, status: 1, children: [
    { id: 31, parentId: 3, categoryName: '自然科学', categoryCode: 'SCI-NT', level: 2, sortOrder: 1, status: 1 },
    { id: 32, parentId: 3, categoryName: '计算机科学', categoryCode: 'SCI-CS', level: 2, sortOrder: 2, status: 1 },
  ]},
]

// 图书
const bookTitles = [
  { title: '红楼梦', author: '曹雪芹', publisher: '人民文学出版社', isbn: '9787020002207', categoryId: 111, location: 'A区1排3架', price: 59.00 },
  { title: '百年孤独', author: '加西亚·马尔克斯', publisher: '南海出版公司', isbn: '9787544291170', categoryId: 12, location: 'B区2排1架', price: 39.50 },
  { title: '史记', author: '司马迁', publisher: '中华书局', isbn: '9787101053434', categoryId: 21, location: 'C区1排2架', price: 128.00 },
  { title: '存在与时间', author: '马丁·海德格尔', publisher: '商务印书馆', isbn: '9787100024421', categoryId: 22, location: 'C区3排5架', price: 78.00 },
  { title: '三体', author: '刘慈欣', publisher: '重庆出版社', isbn: '9787536692930', categoryId: 32, location: 'D区1排1架', price: 93.00 },
  { title: '瓦尔登湖', author: '亨利·梭罗', publisher: '上海译文出版社', isbn: '9787532750933', categoryId: 12, location: 'B区1排3架', price: 28.00 },
  { title: '万历十五年', author: '黄仁宇', publisher: '中华书局', isbn: '9787101055827', categoryId: 21, location: 'C区1排4架', price: 18.00 },
  { title: '理想国', author: '柏拉图', publisher: '商务印书馆', isbn: '9787100014631', categoryId: 22, location: 'C区3排1架', price: 36.00 },
  { title: '时间简史', author: '史蒂芬·霍金', publisher: '湖南科学技术出版社', isbn: '9787535732309', categoryId: 31, location: 'D区2排2架', price: 45.00 },
  { title: '活着', author: '余华', publisher: '作家出版社', isbn: '9787506365437', categoryId: 112, location: 'A区2排2架', price: 25.00 },
  { title: '围城', author: '钱锺书', publisher: '人民文学出版社', isbn: '9787020024759', categoryId: 112, location: 'A区2排3架', price: 19.00 },
  { title: '中国哲学简史', author: '冯友兰', publisher: '商务印书馆', isbn: '9787100024438', categoryId: 22, location: 'C区3排2架', price: 32.00 },
]

export const mockBooks = bookTitles.map((b, i) => ({
  id: i + 1,
  ...b,
  publishDate: `2018-0${(i % 9) + 1}-15`,
  coverUrl: '',
  description: '这是一段简短的图书简介，描述书籍的内容主旨与价值，供读者快速了解。',
  totalCount: [3, 5, 2, 4, 6, 3, 4, 5, 3, 4, 2, 5][i],
  availableCount: [1, 3, 0, 2, 4, 1, 2, 3, 1, 2, 0, 3][i],
  status: 1,
  createTime: '2026-09-01 08:00:00',
}))

// 借阅额度规则 · 按 userType 索引（3=管理员不在表中，即不可借阅）
export const mockBorrowLimits = {
  1: { maxBooks: 10, loanDays: 60, renewMaxCount: 2, renewDays: 30, pickupDays: 7 }, // 教师
  2: { maxBooks: 5,  loanDays: 30, renewMaxCount: 1, renewDays: 15, pickupDays: 3 }, // 学生
}

// 借阅记录
export const mockBorrows = [
  { id: 1, borrowNo: 'BR202609010001', userId: 4, userName: '林知微', bookId: 1, bookTitle: '红楼梦', applyTime: '2026-09-01 10:00:00', borrowTime: '2026-09-01 10:00:00', dueTime: '2026-10-01 10:00:00', returnTime: null, renewCount: 0, renewMaxCount: 1, pickupDeadline: null, status: 1, remark: '' },
  { id: 2, borrowNo: 'BR202609020002', userId: 5, userName: '苏念安', bookId: 2, bookTitle: '百年孤独', applyTime: '2026-08-15 14:30:00', borrowTime: '2026-08-15 14:30:00', dueTime: '2026-09-14 14:30:00', returnTime: null, renewCount: 1, renewMaxCount: 1, pickupDeadline: null, status: 3, remark: '已超期' },
  { id: 3, borrowNo: 'BR202609030003', userId: 4, userName: '林知微', bookId: 5, bookTitle: '三体', applyTime: '2026-08-20 09:15:00', borrowTime: '2026-08-20 09:15:00', dueTime: '2026-09-19 09:15:00', returnTime: '2026-09-10 16:00:00', renewCount: 0, renewMaxCount: 1, pickupDeadline: null, status: 2, remark: '' },
  { id: 4, borrowNo: 'BR202609040004', userId: 2, userName: '李文清', bookId: 3, bookTitle: '史记', applyTime: '2026-09-02 11:00:00', borrowTime: '2026-09-02 11:00:00', dueTime: '2026-11-01 11:00:00', returnTime: null, renewCount: 0, renewMaxCount: 2, pickupDeadline: null, status: 1, remark: '教师借阅' },
  { id: 5, borrowNo: 'BR202609050005', userId: 5, userName: '苏念安', bookId: 7, bookTitle: '万历十五年', applyTime: '2026-09-03 13:45:00', borrowTime: '2026-09-03 13:45:00', dueTime: '2026-10-03 13:45:00', returnTime: null, renewCount: 0, renewMaxCount: 1, pickupDeadline: null, status: 1, remark: '' },
  { id: 6, borrowNo: 'BR202609060006', userId: 3, userName: '陈书韵', bookId: 4, bookTitle: '存在与时间', applyTime: '2026-08-25 10:20:00', borrowTime: '2026-08-25 10:20:00', dueTime: '2026-10-24 10:20:00', returnTime: null, renewCount: 0, renewMaxCount: 2, pickupDeadline: null, status: 1, remark: '教师借阅' },
  { id: 7, borrowNo: 'BR202609070007', userId: 4, userName: '林知微', bookId: 8, bookTitle: '理想国', applyTime: '2026-09-07 09:00:00', borrowTime: null, dueTime: null, returnTime: null, renewCount: 0, renewMaxCount: 1, pickupDeadline: '2026-09-10 09:00:00', status: 0, remark: '待取书' },
]

// 罚款
export const mockFines = [
  { id: 1, userId: 5, userName: '苏念安', borrowRecordId: 2, borrowNo: 'BR202609020002', bookTitle: '百年孤独', amount: 15.00, daysOverdue: 30, reason: '超期未还', status: 0, createTime: '2026-09-15 00:00:00' },
  { id: 2, userId: 4, userName: '林知微', borrowRecordId: 3, borrowNo: 'BR202609030003', bookTitle: '三体', amount: 8.00, daysOverdue: 16, reason: '超期已归还', status: 1, paidTime: '2026-09-10 16:05:00', createTime: '2026-09-10 00:00:00' },
]

// 公告
export const mockAnnouncements = [
  { id: 1, title: '关于 2026 年秋季学期图书馆开放时间的通知', content: '尊敬的师生：秋季学期图书馆开放时间为周一至周日 7:00-22:30，请合理安排借阅时间。', publisherId: 1, publisherName: '系统管理员', top: 1, status: 1, publishTime: '2026-09-01 08:00:00' },
  { id: 2, title: '新书到馆：本月新增哲学类馆藏 50 册', content: '本月新增商务印书馆"汉译世界学术名著丛书"哲学类 50 册，欢迎借阅。', publisherId: 1, publisherName: '系统管理员', top: 0, status: 1, publishTime: '2026-09-05 10:00:00' },
  { id: 3, title: '超期提醒：请及时归还逾期图书', content: '近期有部分图书已逾期，请相关读者尽快归还，避免影响后续借阅。', publisherId: 1, publisherName: '系统管理员', top: 0, status: 1, publishTime: '2026-09-06 14:00:00' },
]

// 通知
export const mockNotifications = [
  { id: 1, userId: 5, type: 2, title: '图书已超期', content: '您借阅的《百年孤独》已超期 30 天，请尽快归还。', isRead: 0, createTime: '2026-09-15 00:00:00' },
  { id: 2, userId: 4, type: 4, title: '归还提醒', content: '您借阅的《红楼梦》将于 10 月 1 日到期。', isRead: 0, createTime: '2026-09-20 09:00:00' },
  { id: 3, userId: 4, type: 1, title: '系统公告', content: '图书馆秋季开放时间已调整。', isRead: 1, createTime: '2026-09-01 08:00:00' },
]

// Dashboard 统计
export const mockDashboard = {
  stats: [
    { key: 'books', label: '馆藏图书', value: 12834, unit: '册', icon: 'Reading', trend: '+128 本月' },
    { key: 'users', label: '在册读者', value: 2156, unit: '人', icon: 'User', trend: '+46 本月' },
    { key: 'borrowing', label: '借阅中', value: 389, unit: '本', icon: 'Tickets', trend: '较上周 +12' },
    { key: 'overdue', label: '逾期未还', value: 23, unit: '本', icon: 'Warning', trend: '待处理', danger: true },
  ],
  // 近 6 个月借阅趋势
  borrowTrend: {
    months: ['4月', '5月', '6月', '7月', '8月', '9月'],
    borrow: [320, 410, 380, 290, 340, 389],
    return: [310, 395, 390, 280, 330, 360],
  },
  // 热门图书 Top5
  hotBooks: [
    { rank: 1, title: '百年孤独', author: '马尔克斯', count: 86 },
    { rank: 2, title: '红楼梦', author: '曹雪芹', count: 72 },
    { rank: 3, title: '三体', author: '刘慈欣', count: 65 },
    { rank: 4, title: '活着', author: '余华', count: 58 },
    { rank: 5, title: '瓦尔登湖', author: '梭罗', count: 43 },
  ],
  // 分类分布
  categoryDist: [
    { name: '文学', value: 5234 },
    { name: '历史·哲学', value: 3120 },
    { name: '科学·技术', value: 4480 },
  ],
}

// 教师 Dashboard · 个人借阅视角（与学生同权，仅额度不同）
export const mockDashboardTeacher = {
  stats: [
    { key: 'borrowing', label: '我的借阅中', value: 4, unit: '本', icon: 'Tickets', trend: '1 本即将到期' },
    { key: 'history', label: '累计借阅', value: 36, unit: '本', icon: 'Reading', trend: '本学期 +12' },
    { key: 'dueSoon', label: '即将到期', value: 1, unit: '本', icon: 'Warning', trend: '7 天内', danger: true },
    { key: 'fine', label: '待缴罚款', value: 0, unit: '元', icon: 'Warning', trend: '无欠款' },
  ],
  borrowTrend: {
    months: ['4月', '5月', '6月', '7月', '8月', '9月'],
    borrow: [4, 6, 5, 8, 7, 9],
    return: [3, 5, 4, 7, 6, 7],
  },
  hotBooks: [
    { rank: 1, title: '史记', author: '司马迁', count: 8 },
    { rank: 2, title: '存在与时间', author: '海德格尔', count: 6 },
    { rank: 3, title: '中国哲学简史', author: '冯友兰', count: 5 },
    { rank: 4, title: '红楼梦', author: '曹雪芹', count: 4 },
    { rank: 5, title: '万历十五年', author: '黄仁宇', count: 3 },
  ],
  categoryDist: [
    { name: '文学', value: 12 },
    { name: '历史·哲学', value: 18 },
    { name: '科学·技术', value: 6 },
  ],
}

// 学生 Dashboard · 个人借阅视角
export const mockDashboardStudent = {
  stats: [
    { key: 'borrowing', label: '我的借阅中', value: 2, unit: '本', icon: 'Tickets', trend: '1 本即将到期' },
    { key: 'history', label: '累计借阅', value: 18, unit: '本', icon: 'Reading', trend: '本学期 +6' },
    { key: 'dueSoon', label: '即将到期', value: 1, unit: '本', icon: 'Warning', trend: '3 天内', danger: true },
    { key: 'fine', label: '待缴罚款', value: 0, unit: '元', icon: 'Warning', trend: '无欠款' },
  ],
  borrowTrend: {
    months: ['4月', '5月', '6月', '7月', '8月', '9月'],
    borrow: [2, 3, 1, 4, 2, 3],
    return: [1, 2, 2, 3, 2, 2],
  },
  hotBooks: [
    { rank: 1, title: '红楼梦', author: '曹雪芹', count: 2 },
    { rank: 2, title: '三体', author: '刘慈欣', count: 1 },
    { rank: 3, title: '活着', author: '余华', count: 1 },
  ],
  categoryDist: [
    { name: '文学', value: 3 },
    { name: '历史·哲学', value: 2 },
    { name: '科学·技术', value: 1 },
  ],
}

// AI 对话历史 mock
export const mockAIConversations = [
  { id: 1, title: '查询我的借阅', model: 'qwen-plus', status: 1, createTime: '2026-09-06 10:00:00' },
  { id: 2, title: '查找哲学类图书', model: 'qwen-plus', status: 1, createTime: '2026-09-06 11:20:00' },
]

export const mockAIMessages = [
  { id: 1, conversationId: 1, role: 'user', content: '帮我查一下我当前借了哪些书？', createTime: '2026-09-06 10:00:00' },
  { id: 2, conversationId: 1, role: 'assistant', content: '您好，林知微同学。您当前借阅中图书 2 本：\n1. 《红楼梦》— 应还日期 2026-10-01\n2. 《三体》— 应还日期 2026-09-19（已逾期 18 天）\n\n建议您尽快归还《三体》以避免产生更多罚款。', createTime: '2026-09-06 10:00:05' },
]

// 模拟登录返回
export function mockLogin(username, password) {
  const user = mockUsers.find((u) => u.username === username)
  if (!user || user.status !== 1) return null
  // mock：任意密码可登录，演示用
  return {
    token: 'mock-token-' + user.id,
    user: {
      id: user.id,
      username: user.username,
      realName: user.realName,
      userType: user.userType,
      roleName: user.roleName,
      avatar: user.avatar || '',
    },
  }
}
