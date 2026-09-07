/* ===== 路由配置 ===== */
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const Layout = () => import('@/layout/index.vue')

// 角色码：1=教师 2=学生 3=管理员
const ALL_ROLES = [1, 2, 3]

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    meta: { hidden: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '概览', icon: 'DataAnalysis', roles: ALL_ROLES },
      },
    ],
  },
  {
    path: '/book',
    component: Layout,
    redirect: '/book/list',
    meta: { title: '图书管理', icon: 'Reading', roles: ALL_ROLES },
    children: [
      {
        path: 'list',
        name: 'BookList',
        component: () => import('@/views/book/list.vue'),
        meta: { title: '馆藏图书', icon: 'Notebook', roles: ALL_ROLES },
      },
      {
        path: 'category',
        name: 'BookCategory',
        component: () => import('@/views/book/category.vue'),
        meta: { title: '图书分类', icon: 'Collection', roles: [3] },
      },
    ],
  },
  {
    path: '/borrow',
    component: Layout,
    redirect: '/borrow/list',
    meta: { title: '借阅管理', icon: 'Tickets', roles: ALL_ROLES },
    children: [
      {
        path: 'list',
        name: 'BorrowList',
        component: () => import('@/views/borrow/list.vue'),
        meta: { title: '借阅记录', icon: 'Document', roles: ALL_ROLES },
      },
      {
        path: 'fine',
        name: 'BorrowFine',
        component: () => import('@/views/borrow/fine.vue'),
        meta: { title: '罚款记录', icon: 'Warning', roles: ALL_ROLES },
      },
    ],
  },
  {
    path: '/user',
    component: Layout,
    redirect: '/user/list',
    meta: { title: '用户管理', icon: 'User', roles: [3] },
    children: [
      {
        path: 'list',
        name: 'UserList',
        component: () => import('@/views/user/list.vue'),
        meta: { title: '读者列表', icon: 'UserFilled', roles: [3] },
      },
    ],
  },
  {
    path: '/ai',
    component: Layout,
    meta: { roles: ALL_ROLES },
    children: [
      {
        path: 'chat',
        name: 'AIChat',
        component: () => import('@/views/ai/chat.vue'),
        meta: { title: 'AI 助手', icon: 'ChatDotRound', roles: ALL_ROLES },
      },
    ],
  },
  {
    path: '/announce',
    component: Layout,
    meta: { roles: ALL_ROLES },
    children: [
      {
        path: 'list',
        name: 'AnnouncementList',
        component: () => import('@/views/announcement/list.vue'),
        meta: { title: '公告通知', icon: 'BellFilled', roles: ALL_ROLES },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} · 智图` : '智图 · 智慧图书管理系统'
  const userStore = useUserStore()
  if (to.meta.public) {
    next()
  } else if (!userStore.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
  } else {
    // 角色权限校验：无 roles 视为公开；用户类型不在允许列表则拒绝
    const allowed = to.meta.roles
    const userType = userStore.userType
    if (allowed && !allowed.includes(userType)) {
      // 无权访问，回退到首页
      next({ path: '/dashboard' })
    } else {
      next()
    }
  }
})

export default router
