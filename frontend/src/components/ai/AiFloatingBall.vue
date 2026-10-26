<template>
  <div class="ai-float-root" v-if="!permanentlyDisabled">
    <!-- 会话模态框（变形目标）：v-show 而非 v-if，关闭后 SSE 流继续在后台输出 -->
    <div ref="modalRef" class="ai-float-modal" :class="{ generating }" v-show="modalMounted">
      <div class="modal-head" ref="headRef">
        <span class="head-mark">智</span>
        <span class="head-title">智能助手</span>
        <span class="ctx-chip" v-if="ctxLabel" :title="ctxLabel">{{ ctxLabel }}</span>
        <el-tooltip content="收起为悬浮球（对话后台继续）" placement="bottom">
          <button class="head-btn" @click="collapse" title="收起">
            <el-icon><Minus /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <AiChatPanel ref="panelRef" @state-change="onPanelState" />
    </div>

    <!-- 悬浮球 -->
    <div ref="ballWrapRef" class="ai-float-wrap" :class="{ docked: isDocked, hidden: ballHidden }">
      <button
        ref="ballRef"
        class="ai-float-ball"
        :class="{ generating }"
        title="AI 助手（右键更多操作）"
        @click="onBallClick"
        @contextmenu.prevent="openContextMenu"
      >
        <span class="ball-text">智</span>
        <span v-if="unreadDone" class="ball-badge" title="AI 回复已完成"></span>
      </button>
    </div>

    <!-- 右键菜单：本次关闭 / 永久关闭 -->
    <transition name="ctx-fade">
      <div
        v-if="ctxMenu.visible"
        class="ball-ctx-menu"
        :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
      >
        <button class="ctx-item" @click="closeSession">
          <el-icon :size="14"><Close /></el-icon>
          <span>本次关闭</span>
        </button>
        <button class="ctx-item ctx-danger" @click="disablePermanently">
          <el-icon :size="14"><CircleClose /></el-icon>
          <span>永久关闭</span>
        </button>
      </div>
    </transition>
  </div>
</template>

<script setup>
/**
 * 全局 AI 悬浮球。
 * - 可拖拽，靠边 1.5s 后自动贴边并半透明化（悬停恢复）
 * - 点击后以「变形」动画展开为会话模态框（模态框标题栏可拖动），关闭时收束回球
 * - 会话进行中收起：SSE 连接保持（面板不销毁），输出完成后球面显示徽标
 * - 球右上角 × 为本次关闭（会话内隐藏）；永久关闭在系统设置/个人中心
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { Minus, Close, CircleClose } from '@element-plus/icons-vue'
import AiChatPanel from '@/components/ai/AiChatPanel.vue'
import { useAiSearchStore } from '@/stores/aiSearch'

const STORAGE_KEY = 'ai_float_disabled'
const DOCK_DELAY_MS = 1500

const route = useRoute()
const modalRef = ref(null)
const headRef = ref(null)
const ballWrapRef = ref(null)
const ballRef = ref(null)
const panelRef = ref(null)

const permanentlyDisabled = ref(false)
const sessionClosed = ref(false)
const modalMounted = ref(false)
const generating = ref(false)
const unreadDone = ref(false)
const isDocked = ref(false)
const ballHidden = ref(false)
// 模态框打开状态（与 modalMounted 区分：mounted 只表示组件已渲染）
const modalOpen = ref(false)

// 当前页面上下文标签（后续接 useAiPageContext 时替换）
const ctxLabel = computed(() => route.meta?.title || '')

// AI 管理页已有完整入口，悬浮球自动隐藏，避免重复
const onAiPage = computed(() => route.path.startsWith('/ai'))

const disabled = computed(() => permanentlyDisabled.value || sessionClosed.value || onAiPage.value)

watch(disabled, (v) => {
  if (v && modalMounted.value && !generating.value) {
    collapse(true)
  }
  ballHidden.value = v && !generating.value
})

/* ---------- 页面唤起请求（图书页「让 AI 帮我找书」等入口） ---------- */
const aiSearchStore = useAiSearchStore()
watch(
  () => aiSearchStore.openRequest,
  async (req) => {
    if (!req?.text) return
    aiSearchStore.consumeOpenRequest()
    sessionClosed.value = false
    if (modalMounted.value) {
      // 面板已存在：直接展开并预填
      await expand()
    } else {
      await expand()
    }
    panelRef.value?.prefill(req.text)
  },
)

/* ---------- 开关持久化 ---------- */
const loadPref = () => {
  permanentlyDisabled.value = localStorage.getItem(STORAGE_KEY) === '1'
}
const setPermanent = (val) => {
  permanentlyDisabled.value = val
  if (val) localStorage.setItem(STORAGE_KEY, '1')
  else localStorage.removeItem(STORAGE_KEY)
}
defineExpose({ setPermanent })
// 提供给设置页/个人中心的全局响应（通过自定义事件通信）
const onToggleEvent = (e) => {
  if (e.detail && typeof e.detail.enabled === 'boolean') setPermanent(!e.detail.enabled)
}
window.addEventListener('ai-float-toggle', onToggleEvent)

/* ---------- 球拖拽 + 贴边吸附 ---------- */
const POS_KEY = 'ai_float_pos'
const BALL = 56
let dragging = false
let moved = false
let startX = 0, startY = 0, startPosX = 0, startPosY = 0
let dockTimer = null

const clampPos = (x, y) => {
  const w = window.innerWidth, h = window.innerHeight
  return {
    x: Math.max(8, Math.min(w - BALL - 8, x)),
    y: Math.max(8, Math.min(h - BALL - 8, y)),
  }
}

const applyPos = (x, y) => {
  const p = clampPos(x, y)
  ballWrapRef.value.style.left = p.x + 'px'
  ballWrapRef.value.style.top = p.y + 'px'
  ballWrapRef.value.style.right = 'auto'
  ballWrapRef.value.style.bottom = 'auto'
}

const savePos = () => {
  const el = ballWrapRef.value
  if (!el) return
  localStorage.setItem(POS_KEY, JSON.stringify({
    x: parseFloat(el.style.left || '0'),
    y: parseFloat(el.style.top || '0'),
  }))
}

const restorePos = () => {
  try {
    const raw = localStorage.getItem(POS_KEY)
    if (raw) {
      const { x, y } = JSON.parse(raw)
      if (typeof x === 'number' && typeof y === 'number') {
        applyPos(x, y)
        maybeDock()
        return
      }
    }
  } catch { /* 忽略损坏数据 */ }
  // 默认右下角
  applyPos(window.innerWidth - BALL - 24, window.innerHeight - BALL - 32)
}

// 靠边（球心距边缘 < 80px）时延时贴边
const maybeDock = () => {
  clearTimeout(dockTimer)
  const el = ballWrapRef.value
  if (!el) return
  const cx = parseFloat(el.style.left || '0') + BALL / 2
  const w = window.innerWidth
  if (cx < 80 || cx > w - 80) {
    const nearRight = cx > w / 2
    dockTimer = setTimeout(() => {
      if (dragging || modalMounted.value) return
      isDocked.value = true
      // 贴边吸附走过渡动画（拖拽时过渡已被改为仅 opacity，此处临时恢复 left 过渡）
      el.style.transition = 'opacity 0.25s ease, left 0.3s ease'
      el.style.left = (nearRight ? w - BALL + 26 : -26) + 'px'
      setTimeout(() => {
        el.style.transition = 'opacity 0.25s ease'
      }, 320)
    }, DOCK_DELAY_MS)
  }
}

const undock = () => {
  clearTimeout(dockTimer)
  if (!isDocked.value) return
  isDocked.value = false
  const el = ballWrapRef.value
  const w = window.innerWidth
  const nearRight = parseFloat(el.style.left || '0') > w / 2
  el.style.left = (nearRight ? w - BALL - 8 : 8) + 'px'
}

const onBallPointerDown = (e) => {
  if (e.button !== 0) return
  dragging = true
  moved = false
  undock()
  // 拖拽期间关闭 left/top 过渡，避免位置更新被过渡拖慢产生阻尼感（贴边吸附前再恢复）
  ballWrapRef.value.style.transition = 'opacity 0.25s ease'
  startX = e.clientX
  startY = e.clientY
  const el = ballWrapRef.value
  startPosX = parseFloat(el.style.left || '0')
  startPosY = parseFloat(el.style.top || '0')
  ballRef.value.setPointerCapture(e.pointerId)
}
const onBallPointerMove = (e) => {
  if (!dragging) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (Math.abs(dx) + Math.abs(dy) > 4) {
    moved = true
    undock()
  }
  applyPos(startPosX + dx, startPosY + dy)
}
const onBallPointerUp = () => {
  if (!dragging) return
  dragging = false
  savePos()
  maybeDock()
}

const onBallClick = () => {
  if (moved) { moved = false; return }
  undock()
  modalOpen.value ? collapse() : expand()
}

const onBallEnter = () => {
  if (isDocked.value) undock()
}

/* ---------- 变形动画 ---------- */
const COLLAPSE_POS_KEY = 'ai_float_collapse_pos'
let animating = false

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

const ballCenter = () => {
  const br = ballWrapRef.value.getBoundingClientRect()
  return { cx: br.left + BALL / 2, cy: br.top + BALL / 2 }
}

// 模态框展开位置：优先球上方，空间不足翻转
const placeModal = () => {
  const { cx, cy } = ballCenter()
  const W = 400, H = Math.min(560, window.innerHeight - 160)
  let x = cx - W / 2
  let y = cy - H - 12
  if (y < 70) y = Math.min(cy + BALL + 12, window.innerHeight - H - 16)
  x = Math.max(12, Math.min(window.innerWidth - W - 12, x))
  return { x, y, w: W, h: H }
}

const expand = async () => {
  if (animating || modalOpen.value) return
  if (!modalMounted.value) {
    modalMounted.value = true
    unreadDone.value = false
    await nextTick()
    panelRef.value?.init()
  }
  animating = true
  const { cx, cy } = ballCenter()
  const pos = placeModal()
  const modal = modalRef.value
  modal.style.width = pos.w + 'px'
  modal.style.height = pos.h + 'px'
  modal.style.transformOrigin = 'center center'

  // 关键：从球心位置/大小展开到目标位置（FLIP 思路），变形始终朝向球
  const fromX = cx - pos.w / 2
  const fromY = cy - pos.h / 2
  modal.style.transform = `translate(${fromX}px, ${fromY}px) scale(0.06)`
  modal.style.opacity = '0'
  modal.style.visibility = 'visible'
  modalOpen.value = true

  if (reducedMotion()) {
    modal.style.transform = `translate(${pos.x}px, ${pos.y}px)`
    modal.style.opacity = '1'
    modalState.x = pos.x; modalState.y = pos.y; modalState.w = pos.w; modalState.h = pos.h
    ballRef.value.style.opacity = '0'
    ballWrapRef.value.style.pointerEvents = 'none'
    animating = false
    return
  }

  const bounce = 'cubic-bezier(0.34, 1.56, 0.64, 1)'
  const shrink = ballRef.value.animate(
    [{ transform: 'scale(1)', opacity: 1 }, { transform: 'scale(0.1)', opacity: 0 }],
    { duration: 200, easing: 'cubic-bezier(0.4, 0, 1, 1)' }
  )
  const grow = modal.animate(
    [
      { transform: `translate(${fromX}px, ${fromY}px) scale(0.06)`, opacity: 0 },
      { transform: `translate(${pos.x}px, ${pos.y}px) scale(1)`, opacity: 1 },
    ],
    { duration: 380, delay: 110, easing: bounce }
  )
  shrink.onfinish = () => {
    ballRef.value.style.opacity = '0'
    ballRef.value.style.transform = 'scale(0.1)'
    ballWrapRef.value.style.pointerEvents = 'none'
  }
  grow.onfinish = () => {
    // 动画结束（无 fill: forwards）后由 inline transform/opacity 接管，模态框拖动不再被动画层压制
    modal.style.transform = `translate(${pos.x}px, ${pos.y}px)`
    modal.style.opacity = '1'
    modalState.x = pos.x; modalState.y = pos.y; modalState.w = pos.w; modalState.h = pos.h
    animating = false
  }
}

const collapse = async (silent = false) => {
  if (!modalMounted.value || animating || !modalOpen.value) return
  animating = true
  modalOpen.value = false
  const modal = modalRef.value
  const { cx, cy } = ballCenter()
  const cur = modalState

  if (reducedMotion()) {
    modal.style.visibility = 'hidden'
    ballRef.value.style.opacity = '1'
    ballWrapRef.value.style.pointerEvents = ''
    animating = false
    return
  }
  const shrink = modal.animate(
    [
      { transform: `translate(${cur.x}px, ${cur.y}px) scale(1)`, opacity: 1 },
      { transform: `translate(${cx - cur.w / 2}px, ${cy - cur.h / 2}px) scale(0.06)`, opacity: 0 },
    ],
    { duration: 280, easing: 'cubic-bezier(0.4, 0, 1, 1)' }
  )
  shrink.onfinish = () => {
    modal.style.visibility = 'hidden'
    ballWrapRef.value.style.pointerEvents = ''
    ballRef.value.style.opacity = '1'
    const bounce = 'cubic-bezier(0.34, 1.56, 0.64, 1)'
    ballRef.value.animate(
      [{ transform: 'scale(0.1)', opacity: 0 }, { transform: 'scale(1)', opacity: 1 }],
      { duration: 320, easing: bounce }
    ).onfinish = () => {
      // 球的动画结束后同样由 inline 样式接管（展开时球 inline opacity 已置 0，此处补回 1）
      ballRef.value.style.transform = ''
      ballRef.value.style.opacity = '1'
      animating = false
    }
  }
}

/* ---------- 模态框标题栏拖动 ---------- */
const modalState = { x: 0, y: 0, w: 400, h: 560 }
let headDragging = false
let headSX = 0, headSY = 0, headOX = 0, headOY = 0

const onHeadDown = (e) => {
  if (e.button !== 0 || e.target.closest('.head-btn')) return
  headDragging = true
  headSX = e.clientX
  headSY = e.clientY
  headOX = modalState.x
  headOY = modalState.y
  headRef.value.setPointerCapture(e.pointerId)
}
const onHeadMove = (e) => {
  if (!headDragging) return
  modalState.x = Math.max(4, Math.min(window.innerWidth - modalState.w - 4, headOX + e.clientX - headSX))
  modalState.y = Math.max(60, Math.min(window.innerHeight - 48, headOY + e.clientY - headSY))
  modalRef.value.style.transform = `translate(${modalState.x}px, ${modalState.y}px)`
}
const onHeadUp = () => { headDragging = false }

/* ---------- 会话状态联动 ---------- */
const onPanelState = (state) => {
  generating.value = state.generating
  if (!state.generating && state.justFinished && !modalMounted.value) {
    unreadDone.value = true
  }
}

/* ---------- 右键菜单 ---------- */
const ctxMenu = reactive({ visible: false, x: 0, y: 0 })
const openContextMenu = (e) => {
  // 菜单定位在鼠标处，右/下越界时向内收
  const w = 128, h = 88
  ctxMenu.x = Math.min(e.clientX, window.innerWidth - w - 8)
  ctxMenu.y = Math.min(e.clientY, window.innerHeight - h - 8)
  ctxMenu.visible = true
}
const hideContextMenu = () => { ctxMenu.visible = false }
const onGlobalClick = () => { hideContextMenu() }

/* ---------- 会话级关闭 ---------- */
const closeSession = () => {
  hideContextMenu()
  sessionClosed.value = true
}
const disablePermanently = () => {
  hideContextMenu()
  setPermanent(true)
  if (modalMounted.value) collapse(true)
}

/* ---------- 生命周期 ---------- */
onMounted(() => {
  loadPref()
  if (!permanentlyDisabled.value) {
    restorePos()
  }
  ballRef.value.addEventListener('pointerdown', onBallPointerDown)
  ballRef.value.addEventListener('pointermove', onBallPointerMove)
  ballRef.value.addEventListener('pointerup', onBallPointerUp)
  ballRef.value.addEventListener('pointercancel', onBallPointerUp)
  ballRef.value.addEventListener('mouseenter', onBallEnter)
  headRef.value.addEventListener('pointerdown', onHeadDown)
  headRef.value.addEventListener('pointermove', onHeadMove)
  headRef.value.addEventListener('pointerup', onHeadUp)
  headRef.value.addEventListener('pointercancel', onHeadUp)
  window.addEventListener('resize', onResize)
  // 全局点击关闭右键菜单（contextmenu 事件本身带 preventDefault，不触发 click）
  window.addEventListener('click', onGlobalClick)
  window.addEventListener('blur', onGlobalClick)
})

const onResize = () => {
  if (!modalMounted.value || animating) return
  const p = clampPos(parseFloat(ballWrapRef.value.style.left || '0'), parseFloat(ballWrapRef.value.style.top || '0'))
  applyPos(p.x, p.y)
  modalState.x = Math.max(4, Math.min(window.innerWidth - modalState.w - 4, modalState.x))
  modalState.y = Math.max(60, Math.min(window.innerHeight - 48, modalState.y))
  modalRef.value.style.transform = `translate(${modalState.x}px, ${modalState.y}px)`
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('click', onGlobalClick)
  window.removeEventListener('blur', onGlobalClick)
  window.removeEventListener('ai-float-toggle', onToggleEvent)
  clearTimeout(dockTimer)
  // 组件卸载时中断面板内可能存在的流式请求
  panelRef.value?.destroy()
})
</script>

<style scoped>
.ai-float-root {
  /* 悬浮球层级：低于 el-dialog(2000+)，高于页面与抽屉遮罩 */
  z-index: 1800;
  position: relative;
}

/* ===== 悬浮球 ===== */
.ai-float-wrap {
  position: fixed;
  width: 56px;
  height: 56px;
  left: 0;
  top: 0;
  z-index: 1800;
  transition: opacity 0.25s ease;
}
.ai-float-wrap.hidden {
  opacity: 0;
  pointer-events: none;
}
.ai-float-wrap.docked {
  opacity: 0.45;
}
.ai-float-wrap.docked:hover {
  opacity: 1;
}

.ai-float-ball {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  cursor: grab;
  background: var(--color-primary, #2f6f4f);
  color: var(--color-text-inverse, #fff);
  font-family: var(--font-serif, serif);
  font-size: 22px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.22);
  position: relative;
  touch-action: none;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}
.ai-float-ball:hover {
  box-shadow: 0 8px 26px rgba(0, 0, 0, 0.28);
  transform: scale(1.05);
}
.ai-float-ball:active { cursor: grabbing; }
.ai-float-ball.generating::after {
  content: '';
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid var(--color-primary, #2f6f4f);
  opacity: 0.6;
  animation: ball-pulse 1.4s ease-out infinite;
}
@keyframes ball-pulse {
  0% { transform: scale(0.85); opacity: 0.7; }
  100% { transform: scale(1.25); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .ai-float-ball.generating::after { animation: none; }
}

.ball-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--color-danger, #c0564a);
  border: 2px solid #fff;
}

/* ===== 右键菜单 ===== */
.ball-ctx-menu {
  position: fixed;
  min-width: 128px;
  background: var(--color-bg-elevated, #fff);
  border: 1px solid var(--color-border, #e5e5e5);
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.16);
  padding: 4px;
  z-index: 1900;
  display: flex;
  flex-direction: column;
}
.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  color: var(--color-text, #333);
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  transition: background 0.12s ease;
}
.ctx-item:hover {
  background: var(--color-bg-subtle, #f4f4f5);
}
.ctx-item.ctx-danger {
  color: var(--color-danger, #c0564a);
}
.ctx-item.ctx-danger:hover {
  background: color-mix(in srgb, var(--color-danger, #c0564a) 10%, transparent);
}
.ctx-fade-enter-active, .ctx-fade-leave-active { transition: opacity 0.12s ease, transform 0.12s ease; }
.ctx-fade-enter-from, .ctx-fade-leave-to { opacity: 0; transform: scale(0.96); }

/* ===== 会话模态框 ===== */
.ai-float-modal {
  position: fixed;
  left: 0;
  top: 0;
  width: 400px;
  height: 560px;
  max-width: calc(100vw - 24px);
  max-height: calc(100vh - 90px);
  background: var(--color-bg-elevated, #fff);
  border: 1px solid var(--color-border, #e5e5e5);
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  visibility: hidden;
  opacity: 0;
  z-index: 1801;
}
.modal-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--color-divider, #eee);
  cursor: move;
  user-select: none;
  touch-action: none;
  flex-shrink: 0;
  background: var(--color-bg-elevated, #fff);
}
.head-mark {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--color-primary, #2f6f4f);
  color: var(--color-text-inverse, #fff);
  font-family: var(--font-serif, serif);
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.head-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text, #333);
}
.ctx-chip {
  font-size: 12px;
  color: var(--color-primary, #2f6f4f);
  background: var(--color-primary-light, #eaf5ef);
  border-radius: 999px;
  padding: 2px 10px;
  margin-left: auto;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-btn {
  width: 26px;
  height: 26px;
  border: 1px solid var(--color-border, #ddd);
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-tertiary, #999);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.head-btn:hover {
  color: var(--color-text, #333);
  border-color: var(--color-text-tertiary, #999);
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
