<template>
  <div class="app-layout">
    <!-- 移动端遮罩 -->
    <div
      v-if="appStore.mobileSidebarOpen"
      class="sidebar-overlay"
      @click="appStore.closeMobileSidebar()"
    ></div>

    <!-- 侧边栏 · 目次式导航 -->
    <aside
      class="app-sidebar"
      :class="{ collapsed: appStore.sidebarCollapsed, 'mobile-open': appStore.mobileSidebarOpen }"
    >
      <div class="sidebar-header">
        <div class="brand">
          <span class="brand-mark">智</span>
          <transition name="fade">
            <span v-if="!appStore.sidebarCollapsed" class="brand-text">
              <span class="brand-name">智图</span>
              <span class="brand-sub">LIBRARY</span>
            </span>
          </transition>
        </div>
      </div>

      <nav class="sidebar-nav">
        <!-- 目次眉题 -->
        <div class="nav-toc" v-if="!appStore.sidebarCollapsed">目次</div>

        <section
          v-for="(section, si) in menuSections"
          :key="si"
          class="menu-section"
          :style="{ animationDelay: `${si * 40}ms` }"
        >
          <!-- 分节眉题：折叠态仅留分隔线 -->
          <div v-if="section.title && !appStore.sidebarCollapsed" class="section-title">
            <span class="section-tick"></span>{{ section.title }}
          </div>

          <el-tooltip
            v-for="item in section.items"
            :key="item.path"
            :content="item.title"
            placement="right"
            :disabled="!appStore.sidebarCollapsed"
            :show-after="200"
          >
            <router-link :to="item.path" class="menu-item" :class="{ active: isActive(item) }">
              <AppIcon :name="item.icon" :size="18" class="menu-icon" />
              <transition name="fade">
                <span v-if="!appStore.sidebarCollapsed" class="menu-label">{{ item.title }}</span>
              </transition>
            </router-link>
          </el-tooltip>
        </section>
      </nav>

      <div class="sidebar-footer" v-if="!appStore.sidebarCollapsed">
        <div class="quote">
          <div class="quote-text">「 静以修身，俭以养德 」</div>
          <div class="quote-author">— 诸葛亮 · 诫子书</div>
        </div>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="app-main">
      <!-- 顶栏 -->
      <header class="app-header">
        <div class="header-left">
          <el-button text @click="appStore.toggleSidebar()" class="collapse-btn">
            <AppIcon name="chevron-left" :size="18" class="collapse-icon" :class="{ flipped: appStore.sidebarCollapsed }" />
          </el-button>
          <AppBreadcrumb />
        </div>

        <div class="header-right">
          <el-tooltip content="切换主题" placement="bottom">
            <el-button text @click="appStore.toggleTheme()" class="icon-btn">
              <AppIcon :name="appStore.theme === 'light' ? 'moon' : 'sun'" :size="18" />
            </el-button>
          </el-tooltip>

          <el-tooltip content="通知" placement="bottom">
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="notify-badge">
              <el-button text class="icon-btn" @click="openNotifyDrawer">
                <AppIcon name="bell" :size="18" />
              </el-button>
            </el-badge>
          </el-tooltip>

          <el-dropdown @command="handleUserCommand" trigger="click">
            <div class="user-info">
              <el-avatar :size="32" class="user-avatar">
                {{ userStore.user?.realName?.charAt(0) || 'U' }}
              </el-avatar>
              <div class="user-text">
                <div class="user-name">{{ userStore.user?.realName }}</div>
                <div class="user-role">{{ userStore.user?.roleName }}</div>
              </div>
              <AppIcon name="chevron-down" :size="14" class="arrow" />
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="app-content">
        <router-view v-slot="{ Component, route: current }">
          <transition name="route" mode="out-in">
            <!-- 列表页常驻缓存（meta.keepAlive），返回时保留查询条件、页码与滚动位置 -->
            <keep-alive :include="cachedViews">
              <component :is="Component" :key="current.name || current.path" />
            </keep-alive>
          </transition>
        </router-view>
      </main>

      <!-- 通知抽屉 -->
      <el-drawer
        v-model="notifyDrawerVisible"
        title="通知"
        direction="rtl"
        size="380px"
        :append-to-body="true"
      >
        <div class="notify-drawer">
          <div class="notify-drawer-head">
            <span class="notify-count">共 {{ notifyList.length }} 条</span>
            <el-button text type="primary" :disabled="unreadCount === 0" @click="onNotifyReadAll">
              全部已读
            </el-button>
          </div>
          <div v-loading="notifyLoading" class="notify-list">
            <div v-if="notifyList.length === 0 && !notifyLoading" class="notify-empty">
              <AppIcon name="bell" :size="30" />
              <p>暂无通知</p>
            </div>
            <div
              v-for="n in notifyList"
              :key="n.id"
              class="notify-item"
              :class="{ 'is-unread': !n.isRead }"
              @click="onNotifyRead(n)"
            >
              <span v-if="!n.isRead" class="notify-dot"></span>
              <div class="notify-body">
                <div class="notify-title">{{ n.title }}</div>
                <div class="notify-content">{{ n.content }}</div>
                <div class="notify-time">{{ formatDateTime(n.createTime) }}</div>
              </div>
            </div>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { notificationApi } from '@/api'
import { formatDateTime } from '@/utils/datetime.js'
import AppIcon from '@/components/AppIcon.vue'
import AppBreadcrumb from '@/components/AppBreadcrumb.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/* ===== 目次式菜单配置 =====
 * 与路由结构解耦：容器路由已拍平（"用户管理>读者列表"这类单子项层级去除），
 * 按"藏书 / 借阅 / 服务 / 平台"四个心智分节组织，roles 控制可见性。
 * 角色码：1=教师 2=学生 3=管理员 */
const ALL = [1, 2, 3]
const MENU_SECTIONS = [
  { title: '', items: [
    { title: '概览', path: '/dashboard', icon: 'gauge', roles: ALL },
  ]},
  { title: '藏书', items: [
    { title: '馆藏图书', path: '/book/list', icon: 'book', roles: ALL },
    { title: '图书分类', path: '/book/category', icon: 'tag', roles: [3] },
  ]},
  { title: '借阅', items: [
    { title: '借阅记录', path: '/borrow/list', icon: 'receipt', roles: ALL },
    { title: '罚款记录', path: '/borrow/fine', icon: 'coin', roles: ALL },
    { title: '读者', path: '/user/list', icon: 'user', roles: [3] },
  ]},
  { title: '服务', items: [
    { title: 'AI 助手', path: '/ai/chat', icon: 'sparkles', roles: ALL },
    { title: '公告', path: '/announce/list', icon: 'bell', roles: ALL },
    // 读者的账户入口（管理员从顶栏进，且其系统设置里已有账户安全）
    { title: '个人中心', path: '/profile', icon: 'user', roles: [1, 2] },
  ]},
  { title: '平台', items: [
    { title: '知识库', path: '/knowledge/list', icon: 'knowledge', roles: [3] },
    { title: '系统设置', path: '/system/settings', icon: 'gear', roles: [3] },
  ]},
]

const menuSections = computed(() => {
  const userType = userStore.userType
  const canAccess = (roles) => !roles || roles.includes(userType)
  return MENU_SECTIONS
    .map((s) => ({ ...s, items: s.items.filter((i) => canAccess(i.roles)) }))
    .filter((s) => s.items.length > 0)
})

// 激活态：支持详情页经由 meta.activeMenu 回亮所属菜单（如 /book/detail/:id → 馆藏图书）
const isActive = (item) => {
  if (route.meta?.activeMenu) return route.meta.activeMenu === item.path
  return route.path === item.path || route.path.startsWith(item.path + '/')
}

// keep-alive 缓存名单：与组件 defineOptions({ name }) 一一对应，故取路由 name
const cachedViews = computed(() =>
  router.getRoutes().filter((r) => r.meta?.keepAlive && r.name).map((r) => r.name)
)

const unreadCount = ref(0)

const loadNotifications = async () => {
  try {
    const data = await notificationApi.unreadCount()
    unreadCount.value = data.count || 0
  } catch (e) {}
}

/* ===== 通知抽屉 ===== */
const notifyDrawerVisible = ref(false)
const notifyList = ref([])
const notifyLoading = ref(false)

const loadNotifyList = async () => {
  notifyLoading.value = true
  try {
    const data = await notificationApi.list()
    notifyList.value = data.list || []
  } catch (e) {
    notifyList.value = []
  } finally {
    notifyLoading.value = false
  }
}

const openNotifyDrawer = async () => {
  notifyDrawerVisible.value = true
  await loadNotifyList()
}

const onNotifyRead = async (n) => {
  if (n.isRead) return
  try {
    await notificationApi.read(n.id)
    n.isRead = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  } catch (e) {
    // 拦截器已提示
  }
}

const onNotifyReadAll = async () => {
  try {
    await notificationApi.readAll()
    notifyList.value.forEach((n) => (n.isRead = 1))
    unreadCount.value = 0
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    // 拦截器已提示
  }
}

const handleUserCommand = (command) => {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}

onMounted(() => {
  loadNotifications()
})
</script>

<style scoped>
.app-layout {
  display: flex;
  min-height: 100vh;
  background: var(--color-bg);
}

/* —— 侧边栏 —— */
.app-sidebar {
  width: var(--sidebar-width);
  background: var(--color-bg-elevated);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  transition: width var(--duration) var(--ease-soft);
  position: sticky;
  top: 0;
  height: 100vh;
  z-index: 20;
}

.app-sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
}

.sidebar-header {
  height: var(--header-height);
  display: flex;
  align-items: center;
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--color-divider);
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.brand-mark {
  width: 32px;
  height: 32px;
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-size: 1.25rem;
  font-weight: 600;
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-text);
  letter-spacing: var(--ls-tight);
}

.brand-sub {
  font-size: 0.5625rem;
  letter-spacing: 0.32em;
  color: var(--color-text-tertiary);
  text-transform: uppercase;
}

/* —— 目次导航 —— */
.sidebar-nav {
  flex: 1;
  padding: var(--space-4) 0 var(--space-2);
  overflow-y: auto;
  overflow-x: hidden;
}

/* "目次" 眉题 · editorial 印刷语汇 */
.nav-toc {
  padding: 0 calc(var(--space-5) + 2px) var(--space-2);
  font-size: var(--fs-caption);
  letter-spacing: 0.42em;
  color: var(--color-text-tertiary);
}

.menu-section {
  display: flex;
  flex-direction: column;
  padding-bottom: var(--space-2);
  margin-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-divider);
  animation: section-in var(--duration) var(--ease-out) both;
}

.menu-section:last-child {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

@keyframes section-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 分节眉题：短刻线 + 小字 */
.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-5) var(--space-2);
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  user-select: none;
}

.section-tick {
  width: 10px;
  height: 1px;
  background: var(--color-border-strong);
  flex-shrink: 0;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  height: 38px;
  padding: 0 calc(var(--space-5) - 6px) 0 var(--space-5);
  margin: 1px var(--space-2);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: var(--fs-body);
  line-height: 1.4;
  transition: color var(--duration-fast) var(--ease-soft),
    background-color var(--duration-fast) var(--ease-soft);
  position: relative;
  text-decoration: none;
  white-space: nowrap;
  overflow: hidden;
}

.menu-icon {
  flex-shrink: 0;
  transition: transform var(--duration-fast) var(--ease-soft);
}

.menu-item:hover {
  color: var(--color-text);
  background: var(--color-bg-subtle);
}

.menu-item:hover .menu-icon {
  transform: translateX(1px);
}

/* 激活态：书签式刻条（垂直居中短条，非通高） */
.menu-item.active {
  color: var(--color-primary);
  background: var(--color-primary-light);
  font-weight: 500;
}

.menu-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 2.5px;
  height: 16px;
  border-radius: 0 2px 2px 0;
  background: var(--color-primary);
}

.menu-item.active .menu-icon {
  color: var(--color-primary);
}

/* 折叠态：图标居中，仅 tooltip 提示 */
.app-sidebar.collapsed .sidebar-nav {
  padding: var(--space-4) 0 var(--space-2);
}

.app-sidebar.collapsed .menu-section {
  margin: 0 var(--space-3) var(--space-2);
  padding-bottom: var(--space-2);
}

.app-sidebar.collapsed .menu-section:last-child {
  margin-bottom: 0;
}

.app-sidebar.collapsed .menu-item {
  justify-content: center;
  padding: 0;
  margin: 1px 0;
}

.sidebar-footer {
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--color-divider);
  flex-shrink: 0;
}

.quote {
  text-align: center;
}

.quote-text {
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  line-height: var(--lh-relaxed);
  margin-bottom: var(--space-2);
}

.quote-author {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
}

/* —— 主区 —— */
.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.app-header {
  height: var(--header-height);
  background: var(--color-bg-elevated);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  position: sticky;
  top: 0;
  z-index: 10;
  backdrop-filter: blur(8px);
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.collapse-btn {
  padding: var(--space-2);
  height: auto;
}

.collapse-icon {
  color: var(--color-text-secondary);
  transition: transform var(--duration) var(--ease-soft);
}

.collapse-icon.flipped {
  transform: rotate(180deg);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.icon-btn {
  padding: var(--space-2);
  height: auto;
  color: var(--color-text-secondary);
}

.notify-badge {
  margin-right: var(--space-2);
}

.user-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  cursor: pointer;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-md);
  transition: background var(--duration-fast) var(--ease-soft);
}

.user-info:hover {
  background: var(--color-bg-subtle);
}

.user-avatar {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  font-family: var(--font-serif);
  font-weight: 600;
}

.user-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.user-name {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--color-text);
}

.user-role {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.arrow {
  color: var(--color-text-tertiary);
}

.app-content {
  flex: 1;
  overflow-x: hidden;
}

/* 通知抽屉 */
.notify-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.notify-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-divider);
}

.notify-count {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

.notify-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0 calc(var(--space-4) * -1);
  padding: 0 var(--space-4);
}

.notify-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: 36px 0;
  color: var(--color-text-tertiary);
  text-align: center;
}

.notify-item {
  display: flex;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-soft);
}

.notify-item:hover {
  background: var(--color-bg-subtle);
}

.notify-item.is-unread {
  background: var(--color-primary-light);
}

.notify-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
  flex-shrink: 0;
  margin-top: 6px;
}

.notify-body {
  flex: 1;
  min-width: 0;
}

.notify-title {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 4px;
}

.notify-content {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.notify-time {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
  margin-top: 6px;
}

/* 过渡动画 */
.fade-enter-active, .fade-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft);
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .user-text { display: none; }
  .app-header { padding: 0 var(--space-4); }
  .header-left .app-breadcrumb { display: none; }
  /* 侧边栏抽屉化 */
  .app-sidebar {
    position: fixed;
    top: 0;
    left: 0;
    height: 100vh;
    z-index: 30;
    transform: translateX(-100%);
    transition: transform var(--duration) var(--ease-soft);
    box-shadow: var(--shadow-lg);
  }
  .app-sidebar.mobile-open {
    transform: translateX(0);
  }
  .app-sidebar.collapsed {
    width: var(--sidebar-width);
  }
  .app-sidebar.collapsed .menu-item {
    justify-content: flex-start;
    padding: 0 calc(var(--space-5) - 6px) 0 var(--space-5);
  }
}

@media (min-width: 769px) {
  .sidebar-overlay { display: none; }
}

.sidebar-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 25;
  backdrop-filter: blur(2px);
}
</style>
