import { onActivated } from 'vue'

/**
 * 缓存列表页的「激活即自动刷新」。
 *
 * keep-alive 让页面不再重建，也就不会再走 onMounted，数据自然陈旧。这里在每次
 * 激活时重新取一份快照并与当前展示的比对，有差异就地替换——不弹遮罩、不重置
 * 页码与筛选，也不需要用户点任何东西；加载提示交给请求层统一的那条顶部进度条。
 *
 * @param {object}   opts
 * @param {Function} opts.fetch 纯取数：返回一份可序列化快照（不改动视图）
 * @param {Function} opts.apply 应用快照：把快照写进页面的响应式状态
 */
export function useSnapshotRefresh({ fetch: fetchSnapshot, apply }) {
  // 当前视图正在展示的快照。null 表示首屏还没加载完——此时不比对，
  // 否则会把「空视图 vs 有数据」误判成有更新，白白多跑一次 apply。
  let current = null
  let checking = false

  /** 页面自身的加载路径：首屏、翻页、搜索。取数后立即应用并推进比对基准 */
  const reload = async () => {
    const snap = await fetchSnapshot()
    apply(snap)
    current = snap
  }

  const check = async () => {
    if (!current || checking) return
    checking = true
    try {
      const snap = await fetchSnapshot()
      // 内容一致就什么都不做，省掉一次无意义的重渲染，进度条也不需要为它亮
      if (JSON.stringify(current) !== JSON.stringify(snap)) {
        apply(snap)
        current = snap
      }
    } catch {
      // 失败保留当前视图（错误提示由请求层统一给出）
    } finally {
      checking = false
    }
  }

  // onActivated 在首次挂载后也会触发一次，由 current === null 兜住
  onActivated(check)

  return { reload }
}
