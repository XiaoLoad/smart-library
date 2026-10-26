/* 应用 store · 主题/侧边栏折叠 */
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    mobileSidebarOpen: false,
    theme: localStorage.getItem('library_theme') || 'light',
  }),
  actions: {
    toggleSidebar() {
      // 移动端切换抽屉，桌面端切换折叠
      if (window.innerWidth <= 768) {
        this.mobileSidebarOpen = !this.mobileSidebarOpen
      } else {
        this.sidebarCollapsed = !this.sidebarCollapsed
      }
    },
    closeMobileSidebar() {
      this.mobileSidebarOpen = false
    },
    toggleTheme() {
      this.theme = this.theme === 'light' ? 'dark' : 'light'
      localStorage.setItem('library_theme', this.theme)
      document.documentElement.setAttribute('data-theme', this.theme)
    },
    initTheme() {
      document.documentElement.setAttribute('data-theme', this.theme)
    },
  },
})
