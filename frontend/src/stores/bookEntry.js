/* 图书详情入口来源记忆层
 * 记录"从哪里点进详情页"，供多处按来源分发：
 * 1. 面包屑二级 crumb：来源是书架显示「馆藏图书」，来源是推荐显示「概览」，来源是 AI 显示对应入口
 * 2. 详情页返回行为：优先 router.back() 回真实来源（保持列表滚动位置等页面状态）
 * 3. 侧栏高亮菜单：按来源回亮对应分节
 * 4. GSAP 合书转场：仅书架来源触发；其他来源走全局路由淡入淡出
 * 5. AI 来源：返回时由悬浮球/AI 助手页恢复对话上下文（见 AiFloatingBall.reopen 与 chat 页 onActivated）
 * 无来源记录（刷新直进详情/新标签）时回退默认书架路径，行为与旧版一致。
 */
import { defineStore } from 'pinia'

export const ENTRY_SOURCES = {
  /** 馆藏图书列表（默认；GSAP 抽书/合书动画路径） */
  LIST: 'BookList',
  /** 仪表盘「为你推荐」轮播卡片 */
  DASHBOARD_RECOMMEND: 'DashboardRecommend',
  /** 图书详情页内的「读过此书的人还读」（详情互跳） */
  DETAIL_SIMILAR: 'BookDetail',
  /** AI 助手页（/ai）内 GenUI 图书卡 */
  AI_ASSISTANT: 'AiAssistant',
  /** 全局 AI 悬浮球面板内 GenUI 图书卡 */
  AI_PANEL: 'AiPanel',
}

/** AI 来源集合：返回后需要恢复 AI 对话上下文 */
export const AI_ENTRY_SOURCES = new Set([ENTRY_SOURCES.AI_ASSISTANT, ENTRY_SOURCES.AI_PANEL])

export const useBookEntryStore = defineStore('bookEntry', {
  state: () => ({
    /** 来源页路由名（ENTRY_SOURCES 之一；null = 无记录，走默认书架） */
    fromName: null,
    /** 来源页完整路径（含 query，用于 router.back 失效时的兜底回退） */
    fromPath: null,
    /** 悬浮球来源：跳转前所在面板会话 id，返回后自动展开面板并恢复该会话 */
    aiPanelConvId: null,
  }),
  getters: {
    /** 是否从书架列表进入（GSAP 书籍转场的既有路径） */
    isFromList: (s) => !s.fromName || s.fromName === ENTRY_SOURCES.LIST,
    /** 面包屑二级文案与路径：无记录时回退书架 */
    crumb: (s) => {
      switch (s.fromName) {
        case ENTRY_SOURCES.DASHBOARD_RECOMMEND:
          return { title: '概览', path: '/dashboard' }
        case ENTRY_SOURCES.AI_ASSISTANT:
          return { title: 'AI 助手', path: '/ai' }
        case ENTRY_SOURCES.AI_PANEL:
          // 悬浮球依附于所在页面，回跳转前的真实页面
          return s.fromPath ? { title: null, path: s.fromPath } : { title: '馆藏图书', path: '/book/list' }
        case ENTRY_SOURCES.DETAIL_SIMILAR:
          // 详情互跳不提供面包屑回跳（会形成详情链），仍展示书架默认项
          return { title: '馆藏图书', path: '/book/list' }
        default:
          return { title: '馆藏图书', path: '/book/list' }
      }
    },
    /** 侧栏高亮菜单：来源是仪表盘时高亮概览，其余高亮馆藏图书 */
    activeMenu: (s) =>
      s.fromName === ENTRY_SOURCES.DASHBOARD_RECOMMEND ? '/dashboard' : '/book/list',
  },
  actions: {
    /** 点击来源卡片进入详情时登记 */
    enter({ fromName, fromPath, aiPanelConvId }) {
      this.fromName = fromName || null
      this.fromPath = fromPath || null
      this.aiPanelConvId = aiPanelConvId || null
    },
    /** 离开详情页后清除，避免下一次进入残留旧来源 */
    leave() {
      this.fromName = null
      this.fromPath = null
      this.aiPanelConvId = null
    },
  },
})
