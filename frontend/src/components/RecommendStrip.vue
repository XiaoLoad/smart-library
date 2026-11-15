<template>
  <!-- 极少数量（≤2）：静态铺排即可填满容器，无需轮播 -->
  <div v-if="items.length && items.length <= 2" class="rec-static">
    <a
      v-for="book in items"
      :key="book.bookId"
      class="rec-card"
      :data-book-id="book.bookId"
      href="javascript:void(0)"
      @click="onCardClick(book, $event)"
    >
      <div class="rec-cover" :class="{ 'no-img': !book.coverUrl || brokenCovers.has(book.bookId) }">
        <img
          v-if="book.coverUrl && !brokenCovers.has(book.bookId)"
          :src="book.coverUrl"
          :alt="book.title"
          loading="lazy"
          @error="brokenCovers.add(book.bookId)"
        />
        <span v-else class="cover-fallback">{{ book.title?.charAt(0) || '书' }}</span>
        <span v-if="(book.availableCount ?? 0) > 0" class="cover-tag">可借</span>
        <span v-else class="cover-tag off">借完</span>
      </div>
      <div class="rec-main">
        <EllipsisText class="rec-title" :content="book.title" :lines="1" />
        <EllipsisText class="rec-author" :content="book.author || '佚名'" />
        <span class="rec-reason" :title="book.reason">
          <el-icon class="reason-icon"><MagicStick /></el-icon>
          <EllipsisText class="reason-text" :content="book.reason" />
        </span>
      </div>
    </a>
  </div>

  <div
    v-else-if="items.length"
    class="rec-carousel"
    @mouseenter="paused = true"
    @mouseleave="onLeave"
  >
    <!-- 轨道：三份 items 拼接实现无缝循环，transform 连续平移 -->
    <div
      ref="trackRef"
      class="rec-track"
      :class="{ dragging: isDragging }"
      :style="trackStyle"
      @pointerdown="onPointerDown"
      @click.capture="onClickCapture"
    >
      <template v-for="(book, idx) in loopItems" :key="idx + '-' + book.bookId">
        <a
          class="rec-card"
          :data-book-id="book.bookId"
          href="javascript:void(0)"
          :draggable="false"
          @click="onCardClick(book, $event)"
        >
          <div class="rec-cover" :class="{ 'no-img': !book.coverUrl || brokenCovers.has(book.bookId) }">
            <img
              v-if="book.coverUrl && !brokenCovers.has(book.bookId)"
              :src="book.coverUrl"
              :alt="book.title"
              loading="lazy"
              draggable="false"
              @error="brokenCovers.add(book.bookId)"
            />
            <span v-else class="cover-fallback">{{ book.title?.charAt(0) || '书' }}</span>
            <!-- 可借角标 -->
            <span v-if="(book.availableCount ?? 0) > 0" class="cover-tag">可借</span>
            <span v-else class="cover-tag off">借完</span>
          </div>
          <div class="rec-main">
            <EllipsisText class="rec-title" :content="book.title" :lines="1" />
            <EllipsisText class="rec-author" :content="book.author || '佚名'" />
            <span class="rec-reason" :title="book.reason">
              <el-icon class="reason-icon"><MagicStick /></el-icon>
              <EllipsisText class="reason-text" :content="book.reason" />
            </span>
          </div>
        </a>
      </template>
    </div>

    <!-- 阅读进度线 -->
    <div class="rec-progress" aria-hidden="true">
      <span class="progress-fill" :style="{ width: progressPercent + '%' }"></span>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { MagicStick } from '@element-plus/icons-vue'
import EllipsisText from '@/components/EllipsisText.vue'
import { useBookEntryStore, ENTRY_SOURCES } from '@/stores/bookEntry'

const props = defineProps({
  items: { type: Array, default: () => [] },
  /** 入口来源：dashboard（默认，仪表盘推荐）/ detail（详情页相似图书互跳） */
  source: { type: String, default: 'dashboard' },
  /** source=detail 时的当前详情图书 ID（用于构造返回路径） */
  sourceBookId: { type: [String, Number], default: null },
})

const brokenCovers = reactive(new Set())
const entryStore = useBookEntryStore()
const router = useRouter()

/* ============ 轮播速度参数（集中可调）============ */
/** 自动播放速度：px/秒。60px/s ≈ 每 2.8 秒推进一张卡，节奏从容不拖沓 */
const SPEED_PX_PER_SEC = 60
/** 首帧渲染后的启动延迟（毫秒）：等首屏布局稳定后再起播，避免与页面入场动画抢帧 */
const START_DELAY_MS = 1200
/** 拖拽判定阈值：水平位移超过该值才视为拖拽（内联点击不受影响） */
const DRAG_THRESHOLD_PX = 6

/* ============ 无缝循环轮播 ============ */
const trackRef = ref(null)
const paused = ref(false)
const offset = ref(0)          // 当前平移 px（恒 ≥0，取模后回卷）
const cardStep = ref(0)        // 单卡宽度 + 间距（实时测量）
const halfWidth = ref(0)       // 单份 items 的轨道总宽
const isDragging = ref(false)
let lastTime = 0               // 上一帧时间戳（速度按真实 dt 计算，帧率波动不影响节奏）

// 三份拼接：滚动超出一份总宽后回卷 offset，肉眼无缝
const loopItems = computed(() => {
  if (!props.items.length) return []
  return [...props.items, ...props.items, ...props.items]
})

const trackStyle = computed(() => ({
  transform: `translate3d(${-offset.value}px, 0, 0)`,
}))

function measure() {
  const track = trackRef.value
  if (!track || !props.items.length) return
  const firstCard = track.querySelector('.rec-card')
  if (!firstCard) return
  const style = getComputedStyle(track)
  const gap = parseFloat(style.columnGap || style.gap) || 0
  cardStep.value = firstCard.getBoundingClientRect().width + gap
  halfWidth.value = cardStep.value * props.items.length
  // 窗口尺寸变化后把 offset 拉回合法区间，避免越界闪跳
  if (halfWidth.value > 0) {
    offset.value = offset.value % halfWidth.value
    if (offset.value < 0) offset.value += halfWidth.value
  }
}

const rafTimer = ref(null)

function tick(now) {
  rafTimer.value = requestAnimationFrame(tick)
  if (paused.value || isDragging.value || cardStep.value <= 0 || halfWidth.value <= 0) {
    lastTime = now
    return
  }
  // 按 dt 计算位移：任何刷新率（60/90/144Hz）下速度一致
  const dt = Math.min(now - lastTime, 64) // 上限 64ms：后台标签页切回时避免跳帧突进
  lastTime = now
  offset.value += (SPEED_PX_PER_SEC * dt) / 1000
  if (offset.value >= halfWidth.value) {
    offset.value -= halfWidth.value // 无缝回卷
  }
}

function onLeave() {
  paused.value = false
}

/**
 * 卡片点击 → 进入详情：
 * 1. 暂停轮播：点击时冻结位置（返回时组件 keep-alive 恢复，卡片还在原处附近）
 * 2. 采样图书信息写入入口来源 store：面包屑/返回/侧栏高亮按来源分发
 * 3. 导航走全局路由淡入淡出过渡
 */
function onCardClick(book, e) {
  if (isDragging.value) return // 拖拽后 260ms 窗口已由 onClickCapture 拦截，这里兜底
  paused.value = true
  entryStore.enter({
    fromName: props.source === 'detail' ? ENTRY_SOURCES.DETAIL_SIMILAR : ENTRY_SOURCES.DASHBOARD_RECOMMEND,
    fromPath: props.source === 'detail' ? `/book/detail/${props.sourceBookId}` : '/dashboard',
  })
  router.push(`/book/detail/${book.bookId}`)
}

/* ============ 拖拽滑动 ============ */
let startX = 0
let startY = 0
let startOffset = 0
let moved = false // 本次按压是否已成判定为拖拽

function onPointerDown(e) {
  // 仅鼠标左键参与拖拽；触屏走原生滚动不受影响
  if (e.pointerType !== 'mouse' || e.button !== 0) return
  if (cardStep.value <= 0) return
  moved = false
  startX = e.clientX
  startY = e.clientY
  startOffset = offset.value
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp, { once: true })
  window.addEventListener('pointercancel', onPointerUp, { once: true })
}

function onPointerMove(e) {
  // 未过阈值前不判定拖拽：普通点击（含轻微手抖）不会进入拖拽态
  const dx = e.clientX - startX
  if (!moved) {
    if (Math.abs(dx) < DRAG_THRESHOLD_PX && Math.abs(e.clientY - startY) < DRAG_THRESHOLD_PX) {
      return
    }
    moved = true
    isDragging.value = true // 过阈值瞬间才冻结自动播放与 hover 视效
  }
  offset.value = (startOffset - dx + halfWidth.value * 3) % halfWidth.value
  if (offset.value < 0) offset.value += halfWidth.value
}

function onPointerUp() {
  window.removeEventListener('pointermove', onPointerMove)
  isDragging.value = false
  // click 由 onClickCapture 在捕获阶段拦截：拖拽后 260ms 内的点击直接吞掉
  if (moved) {
    suppressClickUntil = Date.now() + 260
  }
}

/**
 * 拖拽误触防线（capture 阶段）：
 * router-link 的导航发生在冒泡阶段的 click 默认行为里，
 * 在捕获阶段判定「刚结束一次拖拽」即 stopPropagation + preventDefault，
 * 导航根本不会发起——比事后 router 拦截更干净，也不影响正常点击。
 */
let suppressClickUntil = 0
function onClickCapture(e) {
  if (Date.now() < suppressClickUntil) {
    e.preventDefault()
    e.stopPropagation()
  }
}

/* 进度指示：offset 在一份宽度中的位置 */
const progressPercent = computed(() => {
  if (!halfWidth.value) return 0
  return Math.min(100, Math.round((offset.value / halfWidth.value) * 100))
})

onMounted(() => {
  measure()
  // 延迟启动：等首屏布局稳定后再测量一次（字体加载/图表渲染可能改变卡片宽度）
  setTimeout(() => {
    measure()
    lastTime = performance.now()
    tick(lastTime)
  }, START_DELAY_MS)
  window.addEventListener('resize', measure)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(rafTimer.value)
  window.removeEventListener('resize', measure)
  window.removeEventListener('pointermove', onPointerMove)
})
</script>

<style scoped>
/* ============ 静态模式（≤2 本）============ */
.rec-static {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 260px));
  gap: var(--space-3);
}

/* ============ 轮播容器：书架轨道隐喻 ============ */
.rec-carousel {
  position: relative;
  overflow: hidden;
  /* 两端渐隐：暗示内容横向延展；窄屏时收窄渐隐带避免吞掉过多可视区 */
  --fade: var(--space-5);
  mask-image: linear-gradient(90deg, transparent 0, #000 var(--fade), #000 calc(100% - var(--fade)), transparent 100%);
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 var(--fade), #000 calc(100% - var(--fade)), transparent 100%);
  cursor: grab;
}
.rec-carousel:active {
  cursor: grabbing;
}

.rec-track {
  display: grid;
  grid-auto-flow: column;
  column-gap: var(--space-3);
  will-change: transform;
}
.rec-track.dragging {
  will-change: transform;
}
/* 拖拽中冻结卡片 hover 视效（浮动/阴影/封面缩放），保持抓握手感 */
.rec-track.dragging .rec-card {
  pointer-events: none;
}
.rec-track.dragging .rec-card:hover {
  transform: none;
  box-shadow: none;
}

/* ============ 卡片：书签（Bookmark）式横排紧凑布局 ============ */
/* 尺寸策略：横排「小封面 + 右侧文字」，总高由封面（固定 64px）撑出，
   视口变化只改卡片宽度，高度三档恒定一致；文字区三行锁高，长书名单行截断 */
.rec-card {
  display: flex;
  gap: var(--space-3);
  width: 216px;
  min-width: 216px;
  padding: var(--space-3);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: border-color var(--duration-fast) var(--ease-soft),
              transform var(--duration-fast) var(--ease-soft),
              box-shadow var(--duration) var(--ease-soft);
  user-select: none;
}
/* 首次挂载渐现：骨架屏 → 真实卡片的交接不做生硬替换；
   stagger 错峰（每张延迟 60ms），多卡片按序浮现而非整排跳出。
   动画只播一次（fill backwards 起始透明，播完即静止）。 */
@media (prefers-reduced-motion: no-preference) {
  .rec-card {
    animation: rec-card-in 0.36s var(--ease-out) backwards;
  }
  .rec-track .rec-card:nth-child(2) { animation-delay: 0.06s; }
  .rec-track .rec-card:nth-child(3) { animation-delay: 0.12s; }
  .rec-track .rec-card:nth-child(4) { animation-delay: 0.18s; }
  .rec-track .rec-card:nth-child(5) { animation-delay: 0.24s; }
  .rec-track .rec-card:nth-child(n + 6) { animation-delay: 0.3s; }
  .rec-static .rec-card:nth-child(2) { animation-delay: 0.06s; }
}
@keyframes rec-card-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.rec-card:hover {
  border-color: var(--color-primary);
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

/* ============ 封面：固定小尺寸，比例由容器锁定 ============ */
.rec-cover {
  position: relative;
  width: 48px;
  height: 64px;
  flex-shrink: 0;
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--color-bg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}
.rec-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--duration-slow) var(--ease-out);
}
.rec-card:hover .rec-cover img {
  transform: scale(1.05);
}
.rec-cover.no-img {
  background: linear-gradient(160deg, var(--color-bg-subtle), var(--color-bg-muted));
}
.cover-fallback {
  font-family: var(--font-serif);
  font-size: 1.25rem;
  color: var(--color-text-tertiary);
}
/* 可借角标：盖在封面底部通栏，小封面下比角点更清晰 */
.cover-tag {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  font-size: 9px;
  line-height: 1;
  padding: 2px 0;
  text-align: center;
  color: var(--color-text-inverse);
  background: color-mix(in srgb, var(--color-secondary) 88%, transparent);
  letter-spacing: 0.06em;
}
.cover-tag.off {
  background: color-mix(in srgb, var(--color-text-tertiary) 82%, transparent);
}

/* ============ 文字区 ============ */
/* 行数锁定：title=1 行、author=1 行、reason=1 行，与 64px 封面等高对齐 */
.rec-main {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 3px;
  min-width: 0;
  flex: 1;
  padding: 2px 0;
}
.rec-title {
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  font-weight: 600;
  line-height: var(--lh-snug);
  color: var(--color-text);
  /* EllipsisText 已有 ellipsis 样式；此处兜底锁行高，双保险防撑宽 */
  white-space: nowrap;
}
.rec-author {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rec-reason {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  line-height: 1.3;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 3px 6px;
  max-width: 100%;
  min-width: 0;
}
.reason-icon {
  flex-shrink: 0;
  font-size: 11px;
}
.reason-text {
  min-width: 0;
}

/* ============ 进度线：阅读进度隐喻 ============ */
.rec-progress {
  margin-top: var(--space-3);
  height: 1px;
  background: var(--color-divider);
  border-radius: var(--radius-pill);
  overflow: hidden;
}
.progress-fill {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--color-primary), var(--color-secondary));
  transition: width 0.4s linear;
}
</style>
