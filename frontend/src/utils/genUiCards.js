/* 生成式 UI 内联卡片聚合（chat 页与悬浮球面板共用）
 *
 * B3 相似度门控合并：同一 assistant 消息内多张 book_grid 卡，
 * 按条目 id 重叠率区分「重复检索」与「多面检索」——
 * · 重叠率 ≥ BOOK_MERGE_OVERLAP → 并入同组（union 保序去重），只渲染一张卡
 *   （模型同意图换参数重查的重复卡由此消解；分类+关键词补充检索的并集即完整书单）
 * · 重叠率 < 阈值（如中国史 vs 全球史的多面检索）→ 保持独立卡片，分组语义保留
 * 工具 pill 里的原始 JSON 不受影响（检索轨迹可查），历史消息回放同样生效。
 */

/** 合并判定阈值：交集条目数 / 较小集合条目数 */
export const BOOK_MERGE_OVERLAP = 0.6

/** 快照 JSON → 对象（容忍尾部截断省略号），失败返回 null */
const snapshotOf = (card) => {
  try {
    return JSON.parse((card.data || '').replace(/…$/, ''))
  } catch {
    return null
  }
}

/** 条目去重键：优先 id，其次 isbn，兜底书名 */
const itemKey = (b) =>
  b?.id != null ? `i${b.id}` : b?.isbn ? `s${b.isbn}` : `t${b?.title || ''}`

/** book_grid 卡的条目键集合；数据缺失/异常时返回 null（不参与合并） */
const bookIdSet = (card) => {
  const snap = snapshotOf(card)
  if (!snap || snap.type !== 'book_grid' || !Array.isArray(snap.items) || !snap.items.length) {
    return null
  }
  if (snap.items.some((b) => b?.id == null && !b?.isbn && !b?.title)) return null
  return new Set(snap.items.map(itemKey))
}

/**
 * 聚合同一消息的内联卡片：book_grid 按重叠率分组合并，其余类型保持独立。
 * 返回卡片视图数组——单卡组返回原卡片对象；多卡组返回合并视图（不改原始卡片）：
 * · _mergedData：合并后的快照 JSON 字符串（首卡快照为底，追加后续卡新增条目）
 * · _mergeCount：该组包含的检索次数（>1 时模板可标注「已合并 N 次检索」）
 */
export const groupInlineGenCards = (toolEvents) => {
  const cards = (toolEvents || []).filter((e) => e.phase === 'end' && e._inlineGen)
  const groups = []
  for (const card of cards) {
    const ids = card._genType === 'book_grid' ? bookIdSet(card) : null
    let target = null
    if (ids) {
      for (const g of groups) {
        if (!g.ids) continue
        let inter = 0
        for (const k of ids) if (g.ids.has(k)) inter++
        if (inter / Math.min(ids.size, g.ids.size) >= BOOK_MERGE_OVERLAP) {
          target = g
          break
        }
      }
    }
    if (target) {
      target.cards.push(card)
      for (const k of ids) target.ids.add(k)
    } else {
      groups.push({ ids, cards: [card] })
    }
  }
  return groups.map(({ ids, cards: g }) => {
    const rep = g[0]
    if (g.length === 1) return rep
    const baseSnap = snapshotOf(rep) || {}
    const seen = new Set((baseSnap.items || []).map(itemKey))
    const merged = [...(baseSnap.items || [])]
    for (const c of g.slice(1)) {
      for (const b of snapshotOf(c)?.items || []) {
        const k = itemKey(b)
        if (!seen.has(k)) {
          seen.add(k)
          merged.push(b)
        }
      }
    }
    return {
      ...rep,
      _mergedData: JSON.stringify({ ...baseSnap, items: merged }),
      _mergeCount: g.length,
    }
  })
}

/** 卡片入参摘要：detail 首段（start 阶段的调用参数描述，如「关键词：科幻，分类ID：5」）。
 * 仅 book_grid 卡显示——多面检索的多张卡靠它区分语义分组 */
export const genCardArgLabel = (card) => {
  if (card._genType !== 'book_grid') return ''
  const seg = String(card.detail || '').split(' · ')[0].trim()
  return seg || ''
}
