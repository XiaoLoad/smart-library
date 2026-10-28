/* 图书卡片 ↔ 详情页的共享元素转场
 * begin：列表 → 详情（抽书 + 翻开，翻开即进入详情）
 * beginReturn：详情 → 列表（合书 + 放回原来那张卡片）
 * registerTarget：目标页渲染完成后回传落点位置
 */
import { defineStore } from 'pinia'

export const useBookTransitionStore = defineStore('bookTransition', {
  state: () => ({
    active: false,
    mode: 'forward', // forward | return
    payload: null, // { id, rect, coverUrl, title, author, char }
    target: null, // { rect }
  }),
  actions: {
    begin(payload) {
      this.mode = 'forward'
      this.payload = payload
      this.target = null
      this.active = true
    },
    beginReturn(payload) {
      this.mode = 'return'
      this.payload = payload
      this.target = null
      this.active = true
    },
    registerTarget(el) {
      if (!this.active || !el) return
      const r = el.getBoundingClientRect()
      this.target = {
        rect: { left: r.left, top: r.top, width: r.width, height: r.height },
      }
    },
    finish() {
      this.active = false
      this.mode = 'forward'
      this.payload = null
      this.target = null
    },
  },
})
