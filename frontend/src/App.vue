<template>
  <router-view v-slot="{ Component }">
    <transition name="route" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>

  <!-- 图书卡片 → 详情页的共享元素转场（挂在路由之外，切页时不会被卸载） -->
  <BookTransitionOverlay />
</template>

<script setup>
import BookTransitionOverlay from '@/components/BookTransitionOverlay.vue'
</script>
