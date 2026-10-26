/* 顶部进度条 · 轻量实现（NProgress 风格） */

let started = false
let current = 0
let timer = null

const bar = document.createElement('div')
bar.id = 'global-progress-bar'
bar.style.cssText = `
  position: fixed; top: 0; left: 0; height: 2px; width: 0; z-index: 9999;
  background: var(--color-primary, #3a5a6b);
  box-shadow: 0 0 8px var(--color-primary, #3a5a6b);
  opacity: 0; transition: width 200ms ease, opacity 300ms ease;
  pointer-events: none;
`
document.body.appendChild(bar)

const trickle = () => {
  // 越接近 100% 增速越慢，模拟真实加载
  current += (100 - current) * (current < 50 ? 0.1 : 0.04)
  if (current > 98) current = 98
  bar.style.width = current + '%'
}

export const startProgress = () => {
  if (started) return
  started = true
  current = 0
  bar.style.opacity = '1'
  bar.style.width = '0%'
  timer = setInterval(trickle, 200)
}

export const doneProgress = () => {
  if (!started) return
  started = false
  clearInterval(timer)
  current = 100
  bar.style.width = '100%'
  setTimeout(() => {
    bar.style.opacity = '0'
    setTimeout(() => { bar.style.width = '0%' }, 300)
  }, 200)
}
