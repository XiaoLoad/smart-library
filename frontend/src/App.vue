<template>
  <router-view v-slot="{ Component }">
    <transition name="route" mode="out-in">
      <keep-alive :include="cachedViews">
        <component :is="Component" />
      </keep-alive>
    </transition>
  </router-view>

  <!-- 图书卡片 → 详情页的共享元素转场（挂在路由之外，切页时不会被卸载） -->
  <BookTransitionOverlay />
</template>

<script setup>
import router from '@/router'
import BookTransitionOverlay from '@/components/BookTransitionOverlay.vue'

// 缓存路由表里标记 meta.keepAlive 的页面（组件名 = 路由名，见各页面 defineOptions）。
// 静态收集而非跟随当前路由：include 动态收窄会把已缓存组件踢出缓存
const cachedViews = router
  .getRoutes()
  .filter((r) => r.meta?.keepAlive)
  .map((r) => String(r.name))
</script>
