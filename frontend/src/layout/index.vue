<template>
  <div class="app-layout">
    <!-- 移动端遮罩 -->
    <div
      v-if="appStore.mobileSidebarOpen"
      class="sidebar-overlay"
      @click="appStore.closeMobileSidebar()"
    ></div>

    <!-- 侧边栏 -->
    <aside
      class="app-sidebar"
      :class="{ collapsed: appStore.sidebarCollapsed, 'mobile-open': appStore.mobileSidebarOpen }"
    >
      <div class="sidebar-header">
        <div class="brand">
          <span class="brand-mark">智</span>
          <transition name="fade">
            <span v-if="!appStore.sidebarCollapsed" class="brand-name">智图</span>
          </transition>
        </div>
      </div>

      <nav class="sidebar-nav">
        <template v-for="route in menuRoutes" :key="route.path">
          <!-- 容器路由（meta.hidden）：直接平铺子项为顶层菜单，不显示组标题 -->
          <router-link
            v-if="route.meta?.hidden && route.children?.length"
            v-for="child in route.children.filter(c => !c.meta?.hidden)"
            :key="child.path"
            :to="`${route.path === '/' ? '' : route.path}/${child.path}`"
            class="menu-item"
            @click="appStore.closeMobileSidebar()"
          >
            <el-icon><component :is="child.meta?.icon" /></el-icon>
            <transition name="fade">
              <span v-if="!appStore.sidebarCollapsed">{{ child.meta?.title }}</span>
            </transition>
          </router-link>
          <!-- 普通菜单组：组标题 + 子项 -->
          <div v-else-if="route.children && !route.meta?.hidden" class="menu-group">
            <div class="menu-group__title" v-if="!appStore.sidebarCollapsed && route.meta?.title">
              <el-icon v-if="route.meta?.icon"><component :is="route.meta.icon" /></el-icon>
              <span>{{ route.meta.title }}</span>
            </div>
            <router-link
              v-for="child in route.children.filter(c => !c.meta?.hidden)"
              :key="child.path"
              :to="`${route.path === '/' ? '' : route.path}/${child.path}`"
              class="menu-item"
              @click="appStore.closeMobileSidebar()"
            >
              <el-icon v-if="child.meta?.icon"><component :is="child.meta.icon" /></el-icon>
              <transition name="fade">
                <span v-if="!appStore.sidebarCollapsed">{{ child.meta?.title }}</span>
              </transition>
            </router-link>
          </div>
        </template>
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
          <el-button
            text
            @click="appStore.toggleSidebar()"
            class="collapse-btn"
          >
            <el-icon><Fold v-if="!appStore.sidebarCollapsed" /><Expand v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <!-- 三级面包屑：路由 meta.crumbs（如图书详情页）优先 -->
            <template v-if="route.meta.crumbs">
              <el-breadcrumb-item
                v-for="(c, i) in route.meta.crumbs"
                :key="i"
                :to="c.path ? { path: c.path } : undefined"
              >{{ c.title }}</el-breadcrumb-item>
            </template>
            <el-breadcrumb-item v-else-if="route.meta.title && route.path !== '/dashboard'">
              {{ route.meta.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <el-tooltip content="切换主题" placement="bottom">
            <el-button text @click="appStore.toggleTheme()" class="icon-btn">
              <el-icon><Moon v-if="appStore.theme === 'light'" /><Sunny v-else /></el-icon>
            </el-button>
          </el-tooltip>

          <el-tooltip content="通知" placement="bottom">
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="notify-badge">
              <el-button text class="icon-btn">
                <el-icon><Bell /></el-icon>
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
              <el-icon class="arrow"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="settings">账户设置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="route" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { notificationApi } from '@/api'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

const menuRoutes = computed(() => {
  const userType = userStore.userType
  const canAccess = (roles) => !roles || roles.includes(userType)
  return router.options.routes
    .filter((r) => !r.meta?.public && r.path !== '/login')
    .filter((r) => canAccess(r.meta?.roles))
    .map((r) => ({
      ...r,
      children: (r.children || []).filter((c) => canAccess(c.meta?.roles)),
    }))
    .filter((r) => !r.children || r.children.length > 0)
})

const unreadCount = ref(0)

const loadNotifications = async () => {
  try {
    const data = await notificationApi.list()
    unreadCount.value = data.list.filter((n) => !n.isRead).length
  } catch (e) {}
}

const handleUserCommand = (command) => {
  if (command === 'logout') {
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
  padding: 0 var(--space-5);
  border-bottom: 1px solid var(--color-divider);
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
}

.brand-name {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-text);
  letter-spacing: var(--ls-tight);
}

.sidebar-nav {
  flex: 1;
  padding: var(--space-3) 0;
  overflow-y: auto;
}

.menu-group {
  margin-bottom: var(--space-2);
}

.menu-group__title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5) var(--space-1);
  font-size: var(--fs-caption);
  text-transform: uppercase;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  font-weight: 500;
}

/* 无标题的平铺菜单组（meta.hidden 的容器路由）增加顶部分隔 */
.menu-group + .menu-item,
.sidebar-nav > .menu-item:first-child {
  margin-top: var(--space-1);
}

.menu-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-5);
  margin: 2px var(--space-2);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: var(--fs-body);
  line-height: 1.4;
  transition: all var(--duration-fast) var(--ease-soft);
  position: relative;
  text-decoration: none;
}

.menu-item:hover {
  color: var(--color-text);
  background: var(--color-bg-subtle);
}

.menu-item.router-link-active {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.menu-item.router-link-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--color-primary);
}

.app-sidebar.collapsed .menu-item {
  justify-content: center;
  padding: var(--space-3);
  margin: 2px var(--space-1);
}

.sidebar-footer {
  padding: var(--space-5);
  border-top: 1px solid var(--color-divider);
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
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.icon-btn {
  padding: var(--space-2);
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
  font-size: var(--fs-small);
}

.app-content {
  flex: 1;
  overflow-x: hidden;
}

@media (max-width: 768px) {
  .user-text { display: none; }
  .app-header { padding: 0 var(--space-4); }
  .header-left .el-breadcrumb { display: none; }
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
    padding: var(--space-3) var(--space-5);
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
