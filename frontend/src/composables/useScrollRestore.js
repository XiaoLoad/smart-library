import { onActivated, onDeactivated, onMounted, onBeforeUnmount, nextTick } from 'vue'

/**
 * keep-alive 列表页的滚动位置恢复。
 *
 * 组件被缓存时实例与数据都还在，但 DOM 摘挂会让滚动位置归零——文档滚动与
 * 页面内滚动容器（el-table 表体、分类树等）都在此列。这里不针对具体容器写选择器：
 * scroll 事件虽不冒泡，但会在捕获阶段经过 window，于是挂一个 window 捕获监听
 * 即可覆盖全部滚动容器，位置就近记在元素自身的私有属性上；复位时遍历根节点
 * 下的元素取回，DOM 结构变动也不会失效。
 *
 * 记录只在页面激活期间进行：缓存页被切换走后仍处于 mounted 状态，
 * 若继续记录，就会把别处的滚动位置写进自己的存档。
 */
const MARK = '__keepScrollTop'

/**
 * @param {import('vue').Ref<HTMLElement|null>} rootRef 页面根元素
 */
export function useScrollRestore(rootRef) {
  let docTop = 0
  let attached = false

  const record = (e) => {
    const t = e.target
    // 文档滚动的事件目标是 document
    if (!t || t === document || t === window) {
      docTop = window.scrollY || document.documentElement.scrollTop || 0
      return
    }
    if (t.nodeType === 1) t[MARK] = t.scrollTop
  }

  const attach = () => {
    if (attached) return
    attached = true
    window.addEventListener('scroll', record, { capture: true, passive: true })
  }

  const detach = () => {
    if (!attached) return
    attached = false
    window.removeEventListener('scroll', record, { capture: true })
  }

  const apply = () => {
    // 文档滚动必须无条件复位：本页没滚过也是 0，不能放任它停在上一页的位置
    window.scrollTo(0, docTop)
    const root = rootRef.value
    if (!root) return
    if (root[MARK]) root.scrollTop = root[MARK]
    root.querySelectorAll('*').forEach((el) => {
      if (el[MARK]) el.scrollTop = el[MARK]
    })
  }

  onMounted(attach)

  // 从缓存中重新激活：DOM 挂回后再复位（路由过渡 out-in 结束才会挂载）
  onActivated(() => {
    attach()
    nextTick(() => {
      requestAnimationFrame(() => {
        apply()
        // 表格会在重新挂载后做一次布局测量，可能把表体滚动归零，隔帧再补一次
        requestAnimationFrame(apply)
      })
    })
  })

  onDeactivated(detach)
  onBeforeUnmount(detach)
}
