<template>
  <nav v-if="items.length >= 2" class="conv-nav" :style="{ '--tick-gap': gap + 'px' }" aria-label="对话锚点导航">
    <span class="nav-line" aria-hidden="true"></span>

    <button
      v-for="(it, i) in items"
      :key="it.key"
      type="button"
      class="nav-tick"
      :class="{ active: it.key === displayKey }"
      :aria-label="`第 ${it.seq} 轮：${it.text}`"
      :aria-current="it.key === displayKey ? 'true' : undefined"
      @click="jump(it.key)"
      @mouseenter="showCard(it.key)"
      @mouseleave="hideCard"
      @focus="showCard(it.key)"
      @blur="hideCard"
    >
      <span class="tick-bar" aria-hidden="true"></span>

      <transition name="nav-card">
        <div
          v-if="hoverKey === it.key"
          class="nav-card"
          :class="cardPos(i)"
          @mouseenter="showCard(it.key)"
          @mouseleave="hideCard"
        >
          <div class="card-head">
            <span class="card-seq">第 {{ it.seq }} 轮</span>
            <span class="card-time">{{ it.time }}</span>
          </div>
          <p class="card-text" :title="it.text">{{ it.text }}</p>
        </div>
      </transition>
    </button>
  </nav>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 对话锚点导航（浮动导轨）。
 *
 * 一轮提问 = 一个锚点刻度，悬停浮出该轮完整问题。高亮由 IntersectionObserver
 * 判定带驱动：以聊天滚动容器为 root，把 rootMargin 上下收窄出一条靠上的窄带，
 * 锚点落入带内即为「当前阅读轮次」。相比监听 scroll 计算偏移，IO 不产生滚动
 * 回调开销，且内容流式增高时会自然重算，无需缓存任何位置。
 *
 * 三处边界：
 * 1. 贴底时判定带内往往没有锚点（光标停在长回答末尾），此时强制高亮末节点；
 * 2. 判定带内无锚点时保留上一次高亮，避免滚动过程中高亮闪烁或消失；
 * 3. 点击定位期间锁定目标节点，平滑滚动会途经其他锚点，抵达后交还判定。
 */
const props = defineProps({
  /** 锚点列表：{ key, seq, text, time }，按轮次顺序 */
  items: { type: Array, default: () => [] },
  /** 聊天滚动容器元素 */
  scrollEl: { default: null },
  /** 是否已滚到底部（复用页面的自动跟随状态） */
  atBottom: { type: Boolean, default: false },
})
const emit = defineEmits(['jump'])

const activeKey = ref(null)
const lockedKey = ref(null)
const hoverKey = ref(null)

// 锁定优先于判定：点击定位期间不被途经的锚点抢走高亮
const displayKey = computed(() => lockedKey.value ?? activeKey.value)

// 刻度间距随轮次自适应：短对话舒展，长对话压缩，保证整条导轨不越过输入区
const gap = computed(() =>
  Math.max(3, Math.min(8, Math.floor(420 / props.items.length) - 8))
)

let io = null
let order = []
let inBand = new Set()
let lockTimer = null
let hoverTimer = null

const destroy = () => {
  if (io) {
    io.disconnect()
    io = null
  }
  inBand = new Set()
  order = []
}

const setup = () => {
  destroy()
  const root = props.scrollEl
  if (!root) return
  const els = root.querySelectorAll('[data-anchor]')
  if (els.length < 2) return
  order = Array.from(els, (el) => el.dataset.anchor)
  io = new IntersectionObserver(onIntersect, {
    root,
    // 容器 10%~26% 高度处为判定带：刚滚过顶部的锚点即为当前轮次
    rootMargin: '-10% 0px -74% 0px',
    threshold: 0,
  })
  els.forEach((el) => io.observe(el))
  if (props.atBottom) activeKey.value = order[order.length - 1]
}

const onIntersect = (entries) => {
  for (const e of entries) {
    const key = e.target.dataset.anchor
    if (e.isIntersecting) inBand.add(key)
    else inBand.delete(key)
  }

  if (lockedKey.value) {
    // 目标已进入判定带说明滚到位，交还判定；否则保持锁定
    if (inBand.has(lockedKey.value)) lockedKey.value = null
    else return
  }

  if (props.atBottom) {
    activeKey.value = order[order.length - 1]
    return
  }

  const hit = order.filter((k) => inBand.has(k))
  // 取带内最靠下者：刚进入视野的那一轮更贴近「当前阅读位置」
  if (hit.length) activeKey.value = hit[hit.length - 1]
}

const jump = (key) => {
  const root = props.scrollEl
  const el = root?.querySelector(`[data-anchor="${key}"]`)
  if (!el) return
  lockedKey.value = key
  emit('jump', key)
  el.scrollIntoView({ behavior: 'smooth', block: 'start', inline: 'nearest' })
  // 兜底解锁：平滑滚动的 scrollend 兼容性不足，用超时保证锁定不会僵住
  clearTimeout(lockTimer)
  lockTimer = setTimeout(() => {
    lockedKey.value = null
  }, 1200)
}

// 悬停延迟隐藏：刻度与卡片之间有间隙，无延迟会穿不过去而闪烁
const showCard = (key) => {
  clearTimeout(hoverTimer)
  hoverKey.value = key
}
const hideCard = () => {
  clearTimeout(hoverTimer)
  hoverTimer = setTimeout(() => {
    hoverKey.value = null
  }, 120)
}

// 卡片贴边翻转：靠两端的刻度改为顶部/底部对齐，避免溢出容器被裁切
const cardPos = (i) => {
  if (i < 2) return 'pos-top'
  if (i >= props.items.length - 2) return 'pos-bottom'
  return ''
}

watch(
  () => [props.scrollEl, props.items],
  async () => {
    await nextTick()
    setup()
  },
  { immediate: true }
)

watch(
  () => props.atBottom,
  (v) => {
    if (v && order.length) activeKey.value = order[order.length - 1]
  }
)

onBeforeUnmount(() => {
  destroy()
  clearTimeout(lockTimer)
  clearTimeout(hoverTimer)
})
</script>

<style scoped>
.conv-nav {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  /* 默认不显示：只有消息列两侧留出真正空白时才启用，否则会压住右侧的用户气泡 */
  display: none;
  flex-direction: column;
  gap: var(--tick-gap, 8px);
  z-index: 5;
}

.nav-line {
  position: absolute;
  right: 0;
  top: 6px;
  bottom: 6px;
  width: 1px;
  background: var(--color-border);
}

.nav-tick {
  position: relative;
  width: 18px;
  height: 8px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
}

.tick-bar {
  display: block;
  width: 7px;
  height: 2px;
  border-radius: 1px;
  background: var(--color-border-strong);
  transition: width var(--duration-fast) var(--ease-soft),
    background-color var(--duration-fast) var(--ease-soft);
}

/* 悬停整条导轨时全部刻度轻微延展，给出可点击的暗示 */
.conv-nav:hover .tick-bar,
.nav-tick:hover .tick-bar {
  width: 11px;
}

.nav-tick.active .tick-bar,
.conv-nav:hover .nav-tick.active .tick-bar {
  width: 13px;
  background: var(--color-primary);
}

.nav-tick:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}

/* 悬停卡片 */
.nav-card {
  position: absolute;
  right: calc(100% + 10px);
  top: 50%;
  transform: translateY(-50%);
  width: 260px;
  padding: 10px 12px;
  text-align: left;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
}

.nav-card.pos-top {
  top: 0;
  transform: none;
}

.nav-card.pos-bottom {
  top: auto;
  bottom: 0;
  transform: none;
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: 6px;
}

.card-seq {
  font-family: var(--font-serif);
  font-size: var(--fs-caption);
  color: var(--color-primary);
}

.card-time {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.card-text {
  margin: 0;
  font-size: var(--fs-small);
  line-height: 1.6;
  color: var(--color-text);
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.nav-card-enter-active,
.nav-card-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft);
}

.nav-card-enter-from,
.nav-card-leave-to {
  opacity: 0;
}

/* 消息列限宽 860 居中，窗口足够宽时两侧才有真正空白可放导轨 */
@media (min-width: 1280px) {
  .conv-nav {
    display: flex;
  }
}
</style>
