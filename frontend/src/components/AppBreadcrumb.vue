<template>
  <nav class="app-breadcrumb" aria-label="面包屑导航">
    <ol class="app-breadcrumb__list">
      <template v-for="(item, i) in items" :key="i">
        <li v-if="i > 0" class="app-breadcrumb__sep" aria-hidden="true"></li>
        <li class="app-breadcrumb__item" :class="{ 'is-current': i === items.length - 1 }">
          <router-link
            v-if="item.path && i !== items.length - 1"
            :to="item.path"
            class="app-breadcrumb__link"
          >{{ item.title }}</router-link>
          <span
            v-else
            class="app-breadcrumb__text"
            :aria-current="i === items.length - 1 ? 'page' : undefined"
          >{{ item.title }}</span>
        </li>
      </template>
    </ol>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useBookEntryStore } from '@/stores/bookEntry'

const route = useRoute()
const router = useRouter()
const entryStore = useBookEntryStore()

/* 书眉式面包屑：首页 → [meta.crumbs 或 meta.title]
 * 深层页（如图书详情）走 meta.crumbs 三级结构；概览页只显示当前页，不回指首页。
 * 图书详情的二级 crumb 按入口来源动态化：从推荐进来显示「概览」，
 * 从书架进来（或无来源记录）显示「馆藏图书」——从哪来回哪去。 */
const items = computed(() => {
  const meta = route.meta || {}
  const crumbs = meta.crumbs
  if (Array.isArray(crumbs) && crumbs.length) {
    // 详情页：二级 crumb 用来源记忆覆盖默认书架项；
    // 悬浮球来源 crumb 的 title 由路由 meta 动态解析（见下方解析逻辑）
    const isDetail = route.name === 'BookDetail'
    const mapped = crumbs.map((c, i) => {
      if (isDetail && i === 0) {
        const from = entryStore.crumb
        // 悬浮球来源：显示跳转前所在页面的真实标题（从 meta 表反查）
        if (!from.title && from.path) {
          const target = router.resolve(from.path)
          const t = target.meta?.crumbs
          const last = Array.isArray(t) && t.length ? t[t.length - 1] : null
          return { title: last?.title || target.meta?.title || from.path, path: from.path }
        }
        return { title: from.title, path: from.path }
      }
      return { title: c.title, path: c.path }
    })
    return [
      { title: '首页', path: '/dashboard' },
      ...mapped,
    ]
  }
  if (route.path === '/dashboard') {
    return [{ title: meta.title || '概览' }]
  }
  if (meta.title) {
    return [
      { title: '首页', path: '/dashboard' },
      { title: meta.title },
    ]
  }
  return [{ title: '首页', path: '/dashboard' }]
})
</script>

<style scoped>
.app-breadcrumb {
  min-width: 0;
}

.app-breadcrumb__list {
  display: flex;
  align-items: center;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.app-breadcrumb__item {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

/* 分隔符：细斜线，取书脊/斜杠意象。
   作为独立 flex 子项、纯盒模型而非字形：既天然垂直居中、避免衬线字形基线错位，
   也不把左右边距算进当前页 item 的宽度（否则墨笔下划会延伸到分隔符下方而错位）。 */
.app-breadcrumb__sep {
  flex-shrink: 0;
  width: 1px;
  height: 12px;
  margin: 0 var(--space-3);
  border-radius: 1px;
  background: var(--color-border-strong);
  transform: rotate(15deg);
}

.app-breadcrumb__link,
.app-breadcrumb__text {
  font-family: var(--font-serif);
  font-size: 0.875rem;
  line-height: 1;
  letter-spacing: 0.02em;
  color: var(--color-text-secondary);
  text-decoration: none;
  white-space: nowrap;
  transition: color var(--duration-fast) var(--ease-soft);
}

.app-breadcrumb__link:hover {
  color: var(--color-primary);
}

.app-breadcrumb__link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
  border-radius: var(--radius-sm);
}

/* 当前页：字号提升 + 墨色加重，名字过长时省略。
   max-width 限制单级 crumb 宽度（含三级详情页的书名），超出省略号截断，
   面包屑整体高度恒定，超长书名不会撑开顶栏布局。
   320px 在移动端仍过宽，随视口收窄。 */
.app-breadcrumb__item.is-current {
  position: relative;
  min-width: 0;
  flex-shrink: 1;
  max-width: 40vw;
}

@media (max-width: 768px) {
  .app-breadcrumb__item.is-current {
    max-width: 46vw;
  }
}

.app-breadcrumb__item.is-current .app-breadcrumb__text {
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: 0;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 墨笔下划：由靛蓝渐隐，绝对定位不撑动布局（置于 item 上，避免被文字省略裁切） */
.app-breadcrumb__item.is-current::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -4px;
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, var(--color-primary), transparent);
}
</style>
