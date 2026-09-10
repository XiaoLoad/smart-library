/* ===== 路由配置 ===== */
import { createRouter, createWebHistory } from "vue-router";
import { reactive } from "vue";
import { useUserStore } from "@/stores/user";

const Layout = () => import('@/layout/index.vue')

// 角色码：1=教师 2=学生 3=管理员
const ALL_ROLES = [1, 2, 3]

// 注：侧边栏菜单不再由 meta.icon/容器结构驱动，
// 渲染结构统一由 layout/index.vue 的 MENU_SECTIONS 配置生成（分节 + 统一图标）
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
        meta: { title: '概览', roles: ALL_ROLES },
      },
    ],
  },
  {
    path: '/book',
    component: Layout,
    redirect: '/book/list',
    meta: { hidden: true },
    children: [
      {
        path: 'list',
        name: 'BookList',
        component: () => import('@/views/book/list.vue'),
        meta: { title: '馆藏图书', roles: ALL_ROLES, keepAlive: true },
      },
      {
        path: "detail/:id",
        name: "BookDetail",
        component: () => import("@/views/book/detail.vue"),
        meta: {
          title: "图书详情",
          hidden: true,
          activeMenu: "/book/list",
          roles: ALL_ROLES,
          // 全局面包屑的三级结构：详情页加载后动态写入书名
          crumbs: reactive([
            { title: "馆藏图书", path: "/book/list" },
            { title: "图书详情" },
          ]),
        },
      },
      {
        path: 'category',
        name: 'BookCategory',
        component: () => import('@/views/book/category.vue'),
        meta: { title: '图书分类', roles: [3], keepAlive: true },
      },
    ],
  },
  {
    path: '/borrow',
    component: Layout,
    redirect: '/borrow/list',
    meta: { hidden: true },
    children: [
      {
        path: 'list',
        name: 'BorrowList',
        component: () => import('@/views/borrow/list.vue'),
        meta: { title: '借阅记录', roles: ALL_ROLES, keepAlive: true },
      },
      {
        path: 'fine',
        name: 'BorrowFine',
        component: () => import('@/views/borrow/fine.vue'),
        meta: { title: '罚款记录', roles: ALL_ROLES, keepAlive: true },
      },
    ],
  },
  {
    path: '/user',
    component: Layout,
    redirect: '/user/list',
    meta: { hidden: true },
    children: [
      {
        path: 'list',
        name: 'UserList',
        component: () => import('@/views/user/list.vue'),
        meta: { title: '读者', roles: [3], keepAlive: true },
      },
    ],
  },
  {
    path: '/ai',
    component: Layout,
    redirect: '/ai/chat',
    meta: { hidden: true },
    children: [
      {
        path: 'chat',
        name: 'AIChat',
        component: () => import('@/views/ai/chat.vue'),
        meta: { title: 'AI 助手', roles: ALL_ROLES },
      },
    ],
  },
  {
    path: '/announce',
    component: Layout,
    redirect: '/announce/list',
    meta: { hidden: true },
    children: [
      {
        path: 'list',
        name: 'AnnouncementList',
        component: () => import('@/views/announcement/list.vue'),
        meta: { title: '公告', roles: ALL_ROLES, keepAlive: true },
      },
    ],
  },
  {
    path: '/system',
    component: Layout,
    redirect: '/system/settings',
    meta: { hidden: true },
    children: [
      {
        path: 'settings',
        name: 'SystemSettings',
        component: () => import('@/views/system/settings.vue'),
        meta: { title: '系统设置', roles: [3] },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    // 缓存页由组件自身在 activated 时恢复（含表体内部滚动），此处让位避免两次抢滚动
    if (to.meta?.keepAlive) return false
    // 前进到新页面回到顶部；后退则回到离开时的位置
    return savedPosition || { top: 0 }
  },
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
