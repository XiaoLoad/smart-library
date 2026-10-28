<template>
  <div v-if="store.active && store.payload" class="bt-overlay" aria-hidden="true">
    <div ref="scrimRef" class="bt-scrim"></div>
    <div ref="stageRef" class="bt-stage">
      <div class="bt-book">
        <!-- 内页：封面翻开后露出 -->
        <div class="bt-pages">
          <div class="bt-page-lines"></div>
          <span class="bt-page-title">{{ store.payload.title }}</span>
          <span class="bt-page-sub">{{ store.payload.author || '馆藏' }} · 智图</span>
        </div>

        <!-- 封面：绕书脊翻开 -->
        <div ref="coverRef" class="bt-cover">
          <div class="bt-face bt-face--front">
            <img
              v-if="store.payload.coverUrl && !imgError"
              :src="store.payload.coverUrl"
              alt=""
              @error="imgError = true"
            />
            <span v-else class="bt-char">{{ store.payload.char }}</span>
            <span class="bt-spine"></span>
          </div>
          <div class="bt-face bt-face--back">
            <span class="bt-back-mark">{{ store.payload.char }}</span>
            <span class="bt-back-sub">LIBRARY</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { gsap } from 'gsap'
import { useBookTransitionStore } from '@/stores/bookTransition'

const store = useBookTransitionStore()
const stageRef = ref(null)
const scrimRef = ref(null)
const coverRef = ref(null)
const imgError = ref(false)
const timelines = []
let disposed = false

watch(() => store.payload, () => {
  imgError.value = false
})

/* 等详情页把目标封面位置交回来；超时则降级为原地淡出 */
const waitForTarget = (timeout = 1300) =>
  new Promise((resolve) => {
    if (store.target) return resolve(store.target)
    let stopWatch = null
    let timer = null
    const done = (val) => {
      stopWatch?.()
      clearTimeout(timer)
      resolve(val)
    }
    stopWatch = watch(() => store.target, (t) => {
      if (t) done(t)
    })
    timer = setTimeout(() => done(null), timeout)
  })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* 正向：抽书 → 翻开（翻开即进入详情）→ 书页扩散淡出 */
const runForward = async () => {
  await nextTick()
  const payload = store.payload
  const stage = stageRef.value
  const scrim = scrimRef.value
  const cover = coverRef.value
  if (!payload || !stage || !cover) return

  const vw = window.innerWidth
  const vh = window.innerHeight
  const W = Math.min(Math.max(vw * 0.26, 220), 340)
  const H = (W * 4) / 3
  const centerLeft = (vw - W) / 2
  const centerTop = Math.max(vh * 0.5 - H / 2 - 24, 72)

  // 先落到源卡片的位置，再放大到屏幕中央
  gsap.set(stage, {
    left: payload.rect.left,
    top: payload.rect.top,
    width: payload.rect.width,
    height: payload.rect.height,
    autoAlpha: 1,
  })
  gsap.set(cover, { rotationY: 0 })
  if (scrim) {
    gsap.set(scrim, { opacity: 0 })
    gsap.to(scrim, { opacity: 0.94, duration: 0.32, ease: 'power2.out' })
  }

  const openTl = gsap.timeline()
  openTl.to(stage, {
    left: centerLeft,
    top: centerTop,
    width: W,
    height: H,
    duration: 0.28,
    ease: 'power3.out',
  })
    .to(cover, { rotationY: -166, duration: 0.42, ease: 'power2.inOut' }, '+=0.03')
  timelines.push(openTl)

  const openDone = new Promise((resolve) => openTl.eventCallback('onComplete', resolve))
  const [target] = await Promise.all([waitForTarget(), openDone, sleep(160)])
  if (disposed || !store.active) return

  // 翻开即代表进入详情页；停一拍后把书合上，落到详情页封面的真实位置，再淡出交棒
  const closeTl = gsap.timeline({
    onComplete: () => {
      if (!disposed) store.finish()
    },
  })
  timelines.push(closeTl)

  if (target) {
    const r = target.rect
    closeTl
      .to(cover, { rotationY: 0, duration: 0.2, ease: 'power2.in' })
      .to(stage, {
        left: r.left,
        top: r.top,
        width: r.width,
        height: r.height,
        duration: 0.32,
        ease: 'power3.inOut',
      })
      .to(stage, { autoAlpha: 0, duration: 0.16, ease: 'power1.out' }, '+=0.02')
      .to(scrim, { opacity: 0, duration: 0.2, ease: 'power1.out' }, '<')
  } else {
    // 详情页迟迟没就绪：合上书后原地淡出
    closeTl
      .to(cover, { rotationY: 0, duration: 0.2, ease: 'power2.in' })
      .to(stage, { autoAlpha: 0, scale: 1.06, duration: 0.24, ease: 'power1.out' }, '+=0.02')
      .to(scrim, { opacity: 0, duration: 0.2, ease: 'power1.out' }, '<')
  }
}

/* 返回：书从详情里浮出（已翻开）→ 合上 → 飞回它原来那格书架 → 淡出 */
const runReturn = async () => {
  await nextTick()
  const payload = store.payload
  const stage = stageRef.value
  const scrim = scrimRef.value
  const cover = coverRef.value
  if (!payload || !stage || !cover) return

  const vw = window.innerWidth
  const vh = window.innerHeight
  const W = Math.min(Math.max(vw * 0.26, 220), 340)
  const H = (W * 4) / 3
  const centerLeft = (vw - W) / 2
  const centerTop = Math.max(vh * 0.5 - H / 2 - 24, 72)

  gsap.set(stage, {
    left: centerLeft,
    top: centerTop,
    width: W,
    height: H,
    scale: 1,
    autoAlpha: 0,
  })
  // 详情页对应“翻开的书”，返回时先以翻开态出现，再合上
  gsap.set(cover, { rotationY: -166 })
  if (scrim) {
    gsap.set(scrim, { opacity: 0 })
    gsap.to(scrim, { opacity: 0.94, duration: 0.18, ease: 'power2.out' })
  }

  const enterTl = gsap.timeline()
  enterTl.to(stage, { autoAlpha: 1, duration: 0.12, ease: 'power2.out' })
  timelines.push(enterTl)
  const enterDone = new Promise((resolve) => enterTl.eventCallback('onComplete', resolve))
  await enterDone
  if (disposed || !store.active) return

  // 合书
  const closeTl = gsap.timeline()
  closeTl.to(cover, { rotationY: 0, duration: 0.2, ease: 'power2.inOut' })
  timelines.push(closeTl)
  const closeDone = new Promise((resolve) => closeTl.eventCallback('onComplete', resolve))

  // 列表页激活后会把原卡片的位置交回来；拿不到就原地淡出
  const [target] = await Promise.all([waitForTarget(), closeDone])
  if (disposed || !store.active) return

  const flyTl = gsap.timeline({
    onComplete: () => {
      if (!disposed) store.finish()
    },
  })
  timelines.push(flyTl)

  if (target) {
    const r = target.rect
    flyTl
      .to(stage, {
        left: r.left,
        top: r.top,
        width: r.width,
        height: r.height,
        duration: 0.24,
        ease: 'power3.inOut',
      })
      .to(stage, { autoAlpha: 0, duration: 0.12, ease: 'power1.out' }, '+=0.01')
      .to(scrim, { opacity: 0, duration: 0.16, ease: 'power1.out' }, '<')
  } else {
    flyTl
      .to(stage, { autoAlpha: 0, scale: 0.96, duration: 0.16, ease: 'power1.out' })
      .to(scrim, { opacity: 0, duration: 0.16, ease: 'power1.out' }, '<')
  }
}

const run = () => (store.mode === 'return' ? runReturn() : runForward())

watch(() => store.active, (v) => {
  if (v) run()
}, { flush: 'post' })

onBeforeUnmount(() => {
  disposed = true
  timelines.forEach((t) => t.kill())
})
</script>

<style scoped>
.bt-overlay {
  position: fixed;
  inset: 0;
  z-index: 2600;
  pointer-events: auto;
}

.bt-scrim {
  position: fixed;
  inset: 0;
  background: var(--color-bg);
  opacity: 0;
  pointer-events: none;
}

.bt-stage {
  position: fixed;
  top: 0;
  left: 0;
  will-change: left, top, width, height, opacity;
}

.bt-book {
  position: relative;
  width: 100%;
  height: 100%;
  perspective: 1500px;
  filter: drop-shadow(0 28px 36px rgba(31, 27, 22, 0.32));
}

/* 内页 */
.bt-pages {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border-radius: 3px;
  border: 1px solid rgba(44, 40, 37, 0.14);
  background: linear-gradient(105deg, #f2ece2, #faf8f5 42%, #eee7dc);
}

.bt-page-lines {
  position: absolute;
  inset: 16% 14% 30% 14%;
  background: repeating-linear-gradient(
    to bottom,
    rgba(44, 40, 37, 0.14) 0 1px,
    transparent 1px 14px
  );
  opacity: 0.55;
}

.bt-page-title {
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: 24%;
  font-family: var(--font-serif);
  font-size: clamp(14px, 2.1vw, 20px);
  font-weight: 600;
  line-height: 1.35;
  color: #2c2825;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.bt-page-sub {
  position: absolute;
  left: 14%;
  bottom: 15%;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: #9c958d;
}

/* 封面 */
.bt-cover {
  position: absolute;
  inset: 0;
  transform-style: preserve-3d;
  transform-origin: left center;
  backface-visibility: hidden;
}

.bt-face {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border-radius: 3px;
  backface-visibility: hidden;
}

.bt-face--front {
  border: 1px solid rgba(44, 40, 37, 0.18);
  background: linear-gradient(150deg, #e8eef0, #eef2ea);
}

.bt-face--front img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bt-char {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-size: clamp(40px, 8vw, 64px);
  font-weight: 600;
  color: #3a5a6b;
  opacity: 0.7;
}

.bt-spine {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 8%;
  background: linear-gradient(90deg, rgba(0, 0, 0, 0.26), transparent);
}

.bt-face--back {
  transform: rotateY(180deg);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: linear-gradient(105deg, #f5efe5, #faf8f5);
  border: 1px solid rgba(44, 40, 37, 0.12);
}

.bt-back-mark {
  font-family: var(--font-serif);
  font-size: clamp(26px, 5vw, 38px);
  color: #3a5a6b;
  opacity: 0.7;
}

.bt-back-sub {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.28em;
  color: #9c958d;
}

@media (prefers-reduced-motion: reduce) {
  .bt-overlay {
    display: none;
  }
}
</style>
