/* AI 检索联动 store · AI 助手 queryBooks 结果 → 图书检索页联动展示 */
import { defineStore } from 'pinia'

export const useAiSearchStore = defineStore('aiSearch', {
  state: () => ({
    /**
     * AI 检索结果：{ query, books, total, ts }
     * query = 触发 queryBooks 的用户原话；books = 工具返回的图书数组；ts = 时间戳（触发 watch）
     * null = 无 AI 结果（图书页显示普通检索）
     */
    result: null,
    /** 唤起悬浮球请求：{ text, ts }，由悬浮球组件 watch 并预填发送 */
    openRequest: null,
  }),
  actions: {
    /**
     * 写入 AI 检索结果（AiChatPanel 在 queryBooks 工具 end 时调用）。
     * @param {string} query 用户原话
     * @param {Array} items queryBooks 工具快照 items
     * @param {number} total 工具返回的命中总数
     */
    setResult(query, items, total) {
      this.result = { query, books: items || [], total: total ?? (items || []).length, ts: Date.now() }
    },
    /** 清除 AI 结果（图书页点「返回普通检索」） */
    clear() {
      this.result = null
    },
    /**
     * 请求打开 AI 对话并预填话术（图书页「让 AI 帮我找书」等入口调用）。
     * 悬浮球组件 watch openRequest：打开面板并填入 text（不自动发送，用户确认后发送）。
     */
    requestOpen(text) {
      this.openRequest = { text, ts: Date.now() }
    },
    /** 悬浮球消费后清除请求 */
    consumeOpenRequest() {
      this.openRequest = null
    },
  },
})
