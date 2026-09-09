<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="ICONS[name] || ''"
  />
</template>

<script setup>
/**
 * AppIcon · 站内统一图标体系（iconfont symbol 同款用法，本地化实现）
 *
 * 规范：24×24 viewBox / stroke 1.75 / 圆头圆角 / currentColor（自动适配主题色与暗色模式）
 * 与外部图标库（Element Plus 图标）相比：所有图标出自同一套线宽与端点规范，视觉完全统一；
 * 内联渲染零网络请求，符合"去外链依赖"的性能约定。
 */
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 },
  strokeWidth: { type: [Number, String], default: 1.75 },
})

const ICONS = {
  // 概览 · 半圆仪表
  gauge: '<path d="M5.2 16.4a7.3 7.3 0 1 1 13.6 0"/><path d="M12 14.6l3.4-4.1"/><path d="M4.4 19.7h15.2"/>',
  // 馆藏图书 · 书脊
  book: '<path d="M4.5 19.5A2.5 2.5 0 0 1 7 17h12.5"/><path d="M4.5 19.5V4.5A1.5 1.5 0 0 1 6 3h13.5v14H7a2.5 2.5 0 0 0-2.5 2.5z"/>',
  // 图书分类 · 标签
  tag: '<path d="M4.25 5.5A1.25 1.25 0 0 1 5.5 4.25h5.19a1.5 1.5 0 0 1 1.06.44l7.31 7.31a1.5 1.5 0 0 1 0 2.12l-4.94 4.94a1.5 1.5 0 0 1-2.12 0l-7.31-7.31a1.5 1.5 0 0 1-.44-1.06z"/><circle cx="8.75" cy="8.75" r="1.1"/>',
  // 借阅记录 · 单据
  receipt: '<rect x="5.25" y="3.25" width="13.5" height="17.5" rx="1.75"/><path d="M8.75 8h6.5M8.75 11.5h6.5M8.75 15h3.5"/>',
  // 罚款记录 · 钱币
  coin: '<circle cx="12" cy="12" r="8.75"/><path d="M9.2 7.6l2.8 3.9 2.8-3.9M12 11.5v5M9.4 13.2h5.2M9.4 15.6h5.2"/>',
  // 读者 · 人像
  user: '<circle cx="12" cy="7.75" r="3.5"/><path d="M5.25 19.75a6.75 6.75 0 0 1 13.5 0"/>',
  // AI 助手 · 灵光
  sparkles: '<path d="M11 4.5l1.6 4.15 4.15 1.6-4.15 1.6L11 16l-1.6-4.15-4.15-1.6 4.15-1.6z"/><path d="M18 14.5l.85 2.15 2.15.85-2.15.85-.85 2.15-.85-2.15L15 17.5l2.15-.85z"/>',
  // 公告 · 铃铛
  bell: '<path d="M12 3.75a5.25 5.25 0 0 1 5.25 5.25c0 2.9.7 4.4 1.55 5.55.28.37.02.95-.45.95H5.65c-.47 0-.73-.58-.45-.95.85-1.15 1.55-2.65 1.55-5.55A5.25 5.25 0 0 1 12 3.75z"/><path d="M10.2 18.75a1.8 1.8 0 0 0 3.6 0"/>',
  // 系统设置 · 齿轮
  gear: '<circle cx="12" cy="12" r="3.1"/><path d="M12 3.25v2.7M12 18.05v2.7M3.25 12h2.7M18.05 12h2.7M18.19 5.81l-1.91 1.91M5.81 18.19l1.91-1.91M18.19 18.19l-1.91-1.91M5.81 5.81l1.91 1.91"/>',
  // 折叠侧边栏 · 左箭头
  'chevron-left': '<path d="M14.5 6.75L9.25 12l5.25 5.25"/>',
  // 下拉 · 下箭头
  'chevron-down': '<path d="M6.75 9.5L12 14.75l5.25-5.25"/>',
  // 暗色主题 · 月
  moon: '<path d="M19.75 13.9A7.75 7.75 0 0 1 10.1 4.25 7.75 7.75 0 1 0 19.75 13.9z"/>',
  // 亮色主题 · 日
  sun: '<circle cx="12" cy="12" r="3.9"/><path d="M12 3v2.1M12 18.9V21M3 12h2.1M18.9 12H21M5.64 5.64l1.48 1.48M16.88 16.88l1.48 1.48M18.36 5.64l-1.48 1.48M7.12 16.88l-1.48 1.48"/>',
}
</script>
