const { getIcon } = require('../utils/icons')

const C_NORMAL = '#9c958d'
const C_ACTIVE = '#3a5a6b'

// 与 app.json 的 tabBar.list 同序：书库 0 / 借阅 1 / AI 助手 2 / 消息 3 / 我的 4
const TABS = [
  { route: 'pages/book/index', pagePath: '/pages/book/index', text: '书库', index: 0, icon: getIcon('book', C_NORMAL), activeIcon: getIcon('book', C_ACTIVE) },
  { route: 'pages/borrow/index', pagePath: '/pages/borrow/index', text: '借阅', index: 1, icon: getIcon('scroll', C_NORMAL), activeIcon: getIcon('scroll', C_ACTIVE) },
  { route: 'pages/ai/chat', pagePath: '/pages/ai/chat', text: 'AI 助手', index: 2, icon: getIcon('sparkle', C_NORMAL), activeIcon: getIcon('sparkle', C_ACTIVE) },
  { route: 'pages/message/index', pagePath: '/pages/message/index', text: '消息', index: 3, icon: getIcon('bell', C_NORMAL), activeIcon: getIcon('bell', C_ACTIVE) },
  { route: 'pages/mine/index', pagePath: '/pages/mine/index', text: '我的', index: 4, icon: getIcon('user', C_NORMAL), activeIcon: getIcon('user', C_ACTIVE) },
]

// 为什么没有跨 tab 的滑动动画（改动前请先读完这段，别再试了）：
// 自定义 tabBar 是「每页一个实例」，切页时目标页直接以最终状态渲染。任何跨页入场动画
// 都必须在目标页展示的那一帧校正初始位置，而那一帧的校正必然被渲染出来，表现就是
// 闪动 / 被拽回——框架机制决定的，不是实现技巧问题。
// 微信开放社区 2019 年就在跟踪这个问题，至今标记「暂未解决」；2025 年的新帖里官方承认
// 「自定义 tabbar 切换过程是会有闪烁问题，已经存在很多年了」，且直接 switchTab /
// setData + nextTick / setTimeout 延迟这三种写法都已被验证无效，「页面内先滑完再切页」
// 同样逃不掉切页本身那一下闪。Lin UI 等组件库也把「初次切换必闪」写进了文档免责。
// 结论：选定态只用「图标 / 文字颜色过渡」表达，不做位移。
//
// 选中态由各 tab 页的 onShow 统一驱动：this.getTabBar().setData({ selected: N })
// （官方推荐写法）。本组件只在 attached 里按路由兜一次初值，onTap 里不再 setData——
// 社区已验证 onTap 里额外的 setData 会加重图标闪动。
Component({
  data: {
    selected: 0,
    unread: 0,
    list: TABS,
  },
  lifetimes: {
    attached() {
      const i = this.selfIndex()
      if (i !== null) this.setData({ selected: i })
    },
  },
  methods: {
    // 本实例所属 tab 的下标：由当前页路由推出
    selfIndex() {
      const pages = getCurrentPages()
      const route = pages.length ? pages[pages.length - 1].route : ''
      const i = TABS.findIndex((t) => t.route === route)
      return i < 0 ? null : i
    },
    onTap(e) {
      const idx = Number(e.currentTarget.dataset.index)
      // 重按已选中的 tab：本页回到顶部（微信平台惯例，不新增任何视觉元素）。
      // 页面级滚动的页直接生效；像 AI 助手那样内部滚动的页是一次空操作，无害。
      if (idx === this.data.selected) {
        wx.pageScrollTo({ scrollTop: 0, duration: 320 })
        return
      }
      wx.switchTab({ url: this.data.list[idx].pagePath })
    },
  },
})