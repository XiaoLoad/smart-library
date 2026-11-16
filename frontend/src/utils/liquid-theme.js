/**
 * 液体主题切换 · SVG clipPath 揭示动画（自包含可复用模块，无第三方依赖）
 *
 * 架构（双层渲染）：
 *   - 底层：原 #app，保持旧主题（data-theme 未变），业务状态与输入焦点原封不动。
 *   - 揭示层：#app 的 outerHTML 静态快照，包在 fixed 容器中，根节点改写为
 *     data-theme="新主题"；通过 clip-path: url(#…clip) 只在液体轮廓内可见。
 *   - 单一 requestAnimationFrame 循环，每帧仅重写一个 <path d>（联合轮廓：
 *     液柱摆动 + 液滴 + 飞溅 + 底部波浪），不触发 Vue 重渲染、不读布局。
 *
 * 阶段：pour（液柱从按钮流出、摆动、分离液滴）→ splash（落底水花涟漪）
 *       → rise（波浪液面上涨覆盖全视口）→ 完成 → 真实提交主题并移除临时层。
 *
 * 边界与降级：
 *   - prefers-reduced-motion / 不支持 SVG clipPath → 直接切换。
 *   - 快照是静态 DOM：Canvas（ECharts）显示快照时刻画面，动画结束由业务正常重绘；
 *     <video>、Shadow DOM 内部样式不受快照主题作用域影响。
 *   - 重复点击、resize（少量重算）、滚动（快照视口对齐，原层滚动冻结会破坏交互，
 *     故允许滚动但揭示层 fixed 覆盖视口内已覆盖区域，未覆盖区随原层滚动）、
 *     中断（abort）、页面隐藏、卸载 → 全部清理 rAF / 节点 / 监听。
 *
 * 用法：
 *   import { liquidReveal } from '@/utils/liquid-theme'
 *   liquidReveal({ originEl: btnEl, onCommit: () => appStore.toggleTheme() })
 */

const DEFAULT_DURATION = 2400 // pour+splash ≈ 35%，rise ≈ 65%
const Z_INDEX = 2147483000 // 高于项目 progress(9999) 与一切业务层

let activeSession = null // 单例会话：重复点击直接返回

// 动画期间禁用底层交互（揭示层 fixed 容器本身挡住指针；快照内元素不可聚焦）
const STYLE_PAUSE =
  '*{pointer-events:none!important;}[data-liquid-snapshot] *:focus{outline:none!important;}'

function supportsClipPathSvg() {
  return (
    typeof SVGPathElement !== 'undefined' &&
    'clipPath' in document.documentElement.style &&
    CSS.supports('clip-path', 'url(#x)')
  )
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * 抓取指定主题下的全部 CSS 变量（在 :root 上临时切主题后读取计算值再还原）。
 * 必要性：本项目浅色主题只定义在 :root，暗色靠 html[data-theme="dark"] 覆盖，
 * 没有 [data-theme="light"] 规则。揭示层若仅给内部节点设 data-theme="light"，
 * 仍会从 html[data-theme="dark"] 继承暗色变量 → 深转浅时揭示层还是暗色，
 * 液面与底层同色完全不可见。把变量内联到揭示层容器即可彻底解耦。
 */
function captureThemeTokens(theme) {
  const html = document.documentElement
  const prev = html.getAttribute('data-theme')
  html.setAttribute('data-theme', theme)
  const cs = getComputedStyle(html)
  const tokens = {}
  for (let i = 0; i < cs.length; i++) {
    const name = cs[i]
    // 只取自定义属性（--xxx）
    if (name && name.charCodeAt(0) === 45 && name.charCodeAt(1) === 45) {
      tokens[name] = cs.getPropertyValue(name)
    }
  }
  if (prev === null) html.removeAttribute('data-theme')
  else html.setAttribute('data-theme', prev)
  return tokens
}

/**
 * 生成贝塞尔平滑波浪曲线（从左到右，返回局部 path 片段，以 M 开头）
 */
function wavePath(x0, x1, surfaceY, amp, phase, wavelength) {
  const steps = 14
  let d = `M ${x0.toFixed(1)} ${surfaceY.toFixed(1)}`
  for (let i = 1; i <= steps; i++) {
    const t = i / steps
    const x = x0 + (x1 - x0) * t
    // 双频叠加正弦，相位流动
    const y =
      surfaceY +
      Math.sin(t * Math.PI * 2 * wavelength + phase) * amp +
      Math.sin(t * Math.PI * 2 * wavelength * 2.7 + phase * 1.6) * amp * 0.35
    const cx = x - (x1 - x0) / steps / 2
    d += ` Q ${cx.toFixed(1)} ${(y + amp * 0.2).toFixed(1)} ${x.toFixed(1)} ${y.toFixed(1)}`
  }
  return d
}

/**
 * 把真实 DOM 的运行时状态抄到快照：表单值 / 勾选 / 下拉选中项、
 * 各元素内部滚动位置、2D Canvas 像素。
 * 只读取原节点、只写快照节点，不触发任何业务逻辑或事件。
 */
function syncSnapshotState(src, dst) {
  const srcAll = src.getElementsByTagName('*')
  const dstAll = dst.getElementsByTagName('*')
  const n = Math.min(srcAll.length, dstAll.length)
  for (let i = 0; i < n; i++) {
    const s = srcAll[i]
    const d = dstAll[i]
    const tag = s.tagName
    if (tag === 'INPUT') {
      if (s.type !== 'file') d.value = s.value
      if (s.type === 'checkbox' || s.type === 'radio') d.checked = s.checked
    } else if (tag === 'TEXTAREA') {
      d.value = s.value
    } else if (tag === 'SELECT') {
      d.selectedIndex = s.selectedIndex
    } else if (tag === 'CANVAS' && s.width === d.width && s.height === d.height) {
      // ECharts 等 2D 画布：克隆后是空白，用 drawImage 复制像素
      try {
        const ctx = d.getContext('2d')
        if (ctx) ctx.drawImage(s, 0, 0)
      } catch (e) { /* WebGL / 跨域画布：忽略，保持空白 */ }
    }
    if (s.scrollTop) d.scrollTop = s.scrollTop
    if (s.scrollLeft) d.scrollLeft = s.scrollLeft
  }
}

/**
 * 主入口：执行液体揭示动画。
 * @param {Object} opts
 * @param {Element} opts.originEl     触发按钮（液体从此处流出）
 * @param {Function} opts.onCommit    动画完成时真正提交主题（如 appStore.toggleTheme）
 * @param {number}   [opts.duration]  总时长 ms
 * @returns {{ abort: Function }} 会话句柄
 */
export function liquidReveal(opts) {
  const { originEl, onCommit, duration = DEFAULT_DURATION } = opts || {}
  const finished = { v: false }
  const fromTheme =
    document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'
  const toDark = fromTheme !== 'dark'

  // —— 降级：直接切换（减少动态效果 / 不支持 SVG 裁剪） ——
  if (prefersReducedMotion() || !supportsClipPathSvg()) {
    onCommit?.()
    return { abort: () => {} }
  }

  // —— 重复点击：忽略（不叠加动画与声音） ——
  if (activeSession) return activeSession

  /* ===== 快照与揭示层搭建 ===== */
  const app = document.getElementById('app')
  if (!app) return { abort: () => {} }

  const toTheme = toDark ? 'dark' : 'light'

  const overlay = document.createElement('div')
  overlay.setAttribute('data-liquid-overlay', '')
  overlay.style.cssText = `position:fixed;inset:0;z-index:${Z_INDEX};pointer-events:none;`
  const styleEl = document.createElement('style')
  styleEl.textContent = STYLE_PAUSE
  styleEl.setAttribute('data-liquid-pause', '')

  const snapWrap = document.createElement('div')
  snapWrap.setAttribute('data-liquid-snapshot', '')
  snapWrap.style.cssText =
    'position:absolute;inset:0;clip-path:url(#liquid-clip-path);will-change:clip-path;'
  // 无障碍隔离：不进 Tab 焦点顺序与阅读顺序，也不响应交互
  snapWrap.setAttribute('aria-hidden', 'true')
  snapWrap.setAttribute('inert', '')

  // 快照的滚动容器：让 sticky 元素像真实页面一样吸附，
  // 并可与窗口滚动位置实时同步（快照静态平移会让 sticky 错位）。
  const scroller = document.createElement('div')
  scroller.style.cssText = 'position:absolute;inset:0;overflow:hidden;'
  snapWrap.appendChild(scroller)

  // 把目标主题的全部 CSS 变量内联到揭示层容器（含 --el-*），
  // 使其渲染不再依赖 html 当前的 data-theme。
  const themeTokens = captureThemeTokens(toTheme)
  for (const name in themeTokens) {
    snapWrap.style.setProperty(name, themeTokens[name])
  }
  // 供 :global([data-theme='dark']) 这类后代选择器在快照内命中
  snapWrap.setAttribute('data-theme', toTheme)

  // SVG 舞台：视口尺寸，单 path 联合轮廓
  // 图层顺序（自下而上）：液体填充 → 揭示层内容（被裁剪的新主题）。
  // 液体在元素"背后"流动，新主题的卡片/文字随液面推进从液体中浮现，
  // 而不是盖一层遮罩；液体填充负责液柱/液滴/波浪的可见形体。
  const vw = window.innerWidth
  const vh = window.innerHeight
  const svgNS = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(svgNS, 'svg')
  svg.setAttribute('width', String(vw))
  svg.setAttribute('height', String(vh))
  svg.style.cssText = 'position:absolute;inset:0;pointer-events:none;'
  const defs = document.createElementNS(svgNS, 'defs')
  const clipPathEl = document.createElementNS(svgNS, 'clipPath')
  clipPathEl.setAttribute('id', 'liquid-clip-path')
  clipPathEl.setAttribute('clipPathUnits', 'userSpaceOnUse')
  const pathEl = document.createElementNS(svgNS, 'path')
  pathEl.setAttribute('d', '')
  clipPathEl.appendChild(pathEl)
  defs.appendChild(clipPathEl)
  const liquidEl = document.createElementNS(svgNS, 'path')
  // 液体填充色：与浅→深完全对称的做法，仅颜色不同——
  // 浅→深用深墨色，深→浅用白色（#F4F2F8），不透明。
  liquidEl.setAttribute('fill', toDark ? 'rgb(20,19,25)' : 'rgb(244,242,248)')
  liquidEl.setAttribute('d', '')
  // 液面轮廓线：单独一条 stroke path，只描波浪顶线与液柱，让液体边缘
  // 在同色背景上依然可辨（浅→深用浅色线、深→浅用深色线）。
  const edgeEl = document.createElementNS(svgNS, 'path')
  edgeEl.setAttribute('fill', 'none')
  edgeEl.setAttribute('stroke', toDark ? 'rgba(244,242,248,0.75)' : 'rgba(20,19,25,0.55)')
  edgeEl.setAttribute('stroke-width', '2')
  edgeEl.setAttribute('stroke-linecap', 'round')
  edgeEl.setAttribute('d', '')
  svg.appendChild(defs)
  svg.appendChild(liquidEl)
  svg.appendChild(edgeEl)
  overlay.appendChild(styleEl)
  overlay.appendChild(svg) // 液体在下
  overlay.appendChild(snapWrap) // 新主题内容在上，被裁剪浮现
  document.body.appendChild(overlay)

  /* ===== 快照：DOM 克隆 + 运行时状态同步 ===== */
  // cloneNode 不复制事件监听（避免重复绑定）；随后清掉全部 id（避免重复 ID），
  // 再把表单值、内部滚动位置、Canvas 像素等"运行时状态"逐节点抄过来。
  const snapApp = app.cloneNode(true)
  snapApp.removeAttribute('id')
  snapApp.style.minHeight = '100vh' // 原 #app 的全局样式
  const staleIds = snapApp.querySelectorAll('[id]')
  for (let i = 0; i < staleIds.length; i++) staleIds[i].removeAttribute('id')
  scroller.appendChild(snapApp)
  syncSnapshotState(app, snapApp)

  // 初始滚动对齐：快照滚动容器跟随窗口当前滚动量
  let lastScrollY = window.scrollY || window.pageYOffset || 0
  scroller.scrollTop = lastScrollY

  /* ===== 几何：按按钮实际位置与视口计算 ===== */
  const rect = originEl?.getBoundingClientRect?.() || {
    left: vw / 2, top: 64, width: 32, height: 32,
  }
  const cx = Math.min(vw - 8, Math.max(8, rect.left + rect.width / 2))
  const startY = Math.max(0, rect.top)
  const pourBottom = vh - 6 // 液柱到达的底部
  const pourDur = duration * 0.32
  const riseDur = duration * 0.62
  const settleDur = duration * 0.06

  /* ===== 单 rAF 循环：只更新 path d ===== */
  let raf = 0
  let t0 = 0
  const drips = [] // 分离液滴 {delay, drift, r}

  // 预生成 3~5 颗液滴参数（确定性随机，避免每次渲染变化）
  let seed = 7
  const rnd = () => {
    seed = (seed * 16807) % 2147483647
    return seed / 2147483647
  }
  const dripCount = 3 + Math.floor(rnd() * 3)
  for (let i = 0; i < dripCount; i++) {
    drips.push({
      delay: 0.12 + rnd() * 0.18, // pour 进度点开始脱离
      drift: (rnd() - 0.5) * 34,
      r: 3 + rnd() * 5,
      wobble: rnd() * Math.PI * 2,
    })
  }

  function frame(ts) {
    if (finished.v) return
    if (!t0) t0 = ts
    const elapsed = ts - t0
    const p = Math.min(1, elapsed / duration)

    // 窗口滚动时快照同步跟随（固定延时/静止快照会导致两层错位）
    const sy = window.scrollY || window.pageYOffset || 0
    if (sy !== lastScrollY) {
      lastScrollY = sy
      scroller.scrollTop = sy
    }

    pathEl.setAttribute('d', buildPath(elapsed, p, 6)) // 裁剪区比液体小一圈
    liquidEl.setAttribute('d', buildPath(elapsed, p, 0))
    edgeEl.setAttribute('d', buildEdgePath(elapsed, p))
    raf = requestAnimationFrame(frame)

    if (p >= 1) complete()
  }

  /**
   * 联合轮廓：液柱（摆动变粗细，持续流动到液面上涨后期才收窄消失）+ 液滴 + 底部积水（波浪液面上涨）
   * @param {number} inset 收缩量 px：>0 时轮廓向内收缩（用于裁剪区比液体填充小一圈，
   *                       让液体色边缘始终可见，避免新内容完全盖住液体）
   */
  function buildPath(elapsed, p, inset = 0) {
    const parts = []
    const pourP = Math.min(1, elapsed / pourDur) // 0..1 液柱头下落阶段
    const ease = 1 - Math.pow(1 - pourP, 2.2) // 液柱加速下落
    const riseP = elapsed >= pourDur
      ? Math.min(1, (elapsed - pourDur) / riseDur)
      : 0
    const rp = 1 - Math.pow(1 - riseP, 1.8) // 液面缓出上涨
    // 液面高度：从底部涨到顶（覆盖全视口后再加余量）
    const surfaceY = pourBottom - rp * (pourBottom + 40) - inset
    const amp = 9 + 5 * Math.sin(elapsed / 260) // 持续起伏
    const phase = (elapsed / 1000) * Math.PI * 2 * 0.9

    // —— 液柱：液面还没淹没按钮前持续流动；末端始终接到液面 ——
    if (surfaceY > startY + 24 && riseP < 0.97) {
      const head = Math.min(
        startY + ease * (pourBottom - startY), // 下落阶段
        surfaceY + 2 // 上涨阶段：柱头插入液面，保持连续
      )
      const topW = Math.max(1, 7 + 3 * Math.sin(elapsed / 90) - inset * 2)
      const swing = Math.sin(elapsed / 140) * 9 * Math.min(1, 1.4 - pourP)
      const midX = cx + swing
      const bottomW = Math.max(1, 9 - 5 * Math.min(1, ease) + 2 * Math.sin(elapsed / 120) - inset * 2)

      const d =
        `M ${(cx - topW / 2).toFixed(1)} ${startY.toFixed(1)} ` +
        `C ${(cx - topW / 2 + swing * 0.6).toFixed(1)} ${(startY + (head - startY) * 0.35).toFixed(1)} ` +
        `${(midX - bottomW * 1.4).toFixed(1)} ${(startY + (head - startY) * 0.7).toFixed(1)} ` +
        `${(midX - bottomW).toFixed(1)} ${head.toFixed(1)} ` +
        `L ${(midX + bottomW).toFixed(1)} ${head.toFixed(1)} ` +
        `C ${(midX + bottomW * 1.4).toFixed(1)} ${(startY + (head - startY) * 0.7).toFixed(1)} ` +
        `${(cx + topW / 2 + swing * 0.6).toFixed(1)} ${(startY + (head - startY) * 0.35).toFixed(1)} ` +
        `${(cx + topW / 2).toFixed(1)} ${startY.toFixed(1)} Z`
      parts.push(d)

      // —— 分离液滴：整个流动期间持续从柱身脱离下落 ——
      for (const drip of drips) {
        // 每颗液滴有自己的循环周期，循环生成直至液面接近其轨道
        const cyc = 0.55
        const dp = ((elapsed / 1000) / cyc + drip.wobble) % 1
        const life = dp * 0.85 // 下落寿命
        const dy = startY + (pourBottom - startY) * life
        if (dy > surfaceY - 4) continue // 已入液面则不再画
        const dx = cx + drip.drift * life + Math.sin(life * 9) * 3
        const dr = Math.max(1, drip.r * (1 - life * 0.25) - inset)
        parts.push(
          `M ${(dx - dr).toFixed(1)} ${dy.toFixed(1)} ` +
            `Q ${dx.toFixed(1)} ${(dy - dr * 1.4).toFixed(1)} ${(dx + dr).toFixed(1)} ${dy.toFixed(1)} ` +
            `Q ${dx.toFixed(1)} ${(dy + dr * 1.4).toFixed(1)} ${(dx - dr).toFixed(1)} ${dy.toFixed(1)} Z`
        )
      }
    }

    // —— 底部积水：波浪液面（从 pourDur 起上涨） ——
    if (elapsed >= pourDur) {
      // 水体：波浪顶线 + 底部闭合到视口底
      const d =
        wavePath(-20, vw + 20, surfaceY, Math.max(1, amp - inset), phase, 1.6) +
        ` L ${vw + 20} ${vh + 20} L -20 ${vh + 20} Z`
      parts.push(d)

      // 落点涟漪：落底后一小段时间内的扩散环（融入联合轮廓）
      const splashAge = elapsed - pourDur
      if (splashAge < 420) {
        const rr = 8 + splashAge / 420 * 46
        const rw = Math.max(2, 9 - splashAge / 420 * 7)
        const ry = pourBottom
        parts.push(
          `M ${(cx - rr).toFixed(1)} ${ry.toFixed(1)} ` +
            `Q ${cx.toFixed(1)} ${(ry - rw - 6).toFixed(1)} ${(cx + rr).toFixed(1)} ${ry.toFixed(1)} ` +
            `Q ${cx.toFixed(1)} ${(ry - rw * 0.2).toFixed(1)} ${(cx - rr).toFixed(1)} ${ry.toFixed(1)} Z`
        )
      }
    }

    return parts.join(' ')
  }

  /** 边缘轮廓（stroke 用）：液柱两侧线 + 波浪液面顶线 + 液滴圆环 */
  function buildEdgePath(elapsed, p) {
    const segs = []
    const pourP = Math.min(1, elapsed / pourDur)
    const ease = 1 - Math.pow(1 - pourP, 2.2)
    const riseP = elapsed >= pourDur ? Math.min(1, (elapsed - pourDur) / riseDur) : 0
    const rp = 1 - Math.pow(1 - riseP, 1.8)
    const surfaceY = pourBottom - rp * (pourBottom + 40)
    const amp = 9 + 5 * Math.sin(elapsed / 260)
    const phase = (elapsed / 1000) * Math.PI * 2 * 0.9

    // 液柱两侧（与 buildPath 中柱体一致）
    if (surfaceY > startY + 24 && riseP < 0.97) {
      const head = Math.min(startY + ease * (pourBottom - startY), surfaceY + 2)
      const topW = Math.max(1, 7 + 3 * Math.sin(elapsed / 90))
      const swing = Math.sin(elapsed / 140) * 9 * Math.min(1, 1.4 - pourP)
      const midX = cx + swing
      const bottomW = Math.max(1, 9 - 5 * Math.min(1, ease) + 2 * Math.sin(elapsed / 120))
      segs.push(
        `M ${(cx - topW / 2).toFixed(1)} ${startY.toFixed(1)} ` +
        `C ${(cx - topW / 2 + swing * 0.6).toFixed(1)} ${(startY + (head - startY) * 0.35).toFixed(1)} ` +
        `${(midX - bottomW * 1.4).toFixed(1)} ${(startY + (head - startY) * 0.7).toFixed(1)} ` +
        `${(midX - bottomW).toFixed(1)} ${head.toFixed(1)}`
      )
      segs.push(
        `M ${(cx + topW / 2).toFixed(1)} ${startY.toFixed(1)} ` +
        `C ${(cx + topW / 2 + swing * 0.6).toFixed(1)} ${(startY + (head - startY) * 0.35).toFixed(1)} ` +
        `${(midX + bottomW * 1.4).toFixed(1)} ${(startY + (head - startY) * 0.7).toFixed(1)} ` +
        `${(midX + bottomW).toFixed(1)} ${head.toFixed(1)}`
      )
    }

    // 波浪液面顶线
    if (elapsed >= pourDur) {
      segs.push(wavePath(-20, vw + 20, surfaceY, Math.max(1, amp), phase, 1.6))
    }
    return segs.join(' ')
  }

  /* ===== 完成 / 中断 / 清理 ===== */
  let listeners = []
  function addListener(target, type, fn, opt) {
    target.addEventListener(type, fn, opt)
    listeners.push([target, type, fn, opt])
  }

  function cleanup() {
    if (raf) cancelAnimationFrame(raf)
    raf = 0
    for (const [t, type, fn, opt] of listeners) t.removeEventListener(type, fn, opt)
    listeners = []
    overlay.remove()
    styleEl.remove()
    activeSession = null
    finished.v = true
  }

  function complete() {
    cleanup()
    // 提交主题瞬间禁用全页过渡，避免 Element 表格等组件的
    // background/color transition 产生一帧闪白；下一帧恢复。
    const root = document.documentElement
    const noTrans = document.createElement('style')
    noTrans.textContent =
      '*,*::before,*::after{transition:none!important;animation-duration:0s!important;}'
    root.appendChild(noTrans)
    onCommit?.() // 真实提交主题（此时揭示层已覆盖全屏，切换无闪烁）
    requestAnimationFrame(() => requestAnimationFrame(() => noTrans.remove()))
  }

  function abort() {
    if (finished.v) return
    cleanup()
  }

  // 窗口尺寸变化：小视口跳动可容忍；跨度大（如拖拽窗、旋屏）时中断避免错位
  let resizeTimer = 0
  addListener(window, 'resize', () => {
    clearTimeout(resizeTimer)
    resizeTimer = setTimeout(() => {
      const r2 = originEl?.getBoundingClientRect?.()
      if (!r2 || Math.abs(r2.left + r2.width / 2 - cx) > 80) abort()
    }, 120)
  })

  // Esc 中断；页面隐藏暂停（visibilitychange 时 rAF 自动停帧，回来继续）
  addListener(window, 'keydown', (e) => {
    if (e.key === 'Escape') abort()
  })

  // 启动
  raf = requestAnimationFrame(frame)

  const session = { abort }
  activeSession = session
  return session
}

/** 是否有动画进行中（供 UI 禁用按钮等） */
export function isLiquidRevealActive() {
  return !!activeSession
}
