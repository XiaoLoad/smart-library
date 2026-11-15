/**
 * AI 流式回答的自适应节流渲染器（借鉴 deepseek-ai/chat-demo 的
 * useThrottleMarkdownRender 思路）。
 *
 * 问题：delta 每次到达都全量 renderMarkdown + 赋值 msg.html，长回答时
 * markdown 解析频率高达每秒几十次，主线程被解析占满，滚动与交互掉帧。
 *
 * 方案：渲染调用与实际执行解耦——调用方每次 delta 到达时调 schedule()，
 * 由定时器决定真正的渲染时机；间隔按上一次渲染耗时自适应（耗时 ×2，
 * 下限 80ms 上限 2000ms）：渲染快说明机器有空，跟得紧一点；
 * 渲染慢说明正文已长，自动放宽间隔保帧率。
 *
 * 流结束时必须调用 flush() 把最终内容立即渲染上屏（否则结尾缺字）。
 *
 * 无状态单实例不够用（chat 页与悬浮球面板可能同时各有一条流在渲染），
 * 由调用方为每条流创建独立实例。
 */
export function createStreamRenderer() {
  let timer = null
  let lastRenderCost = 0
  let pendingFn = null

  /** 上次渲染耗时推演出的下一个间隔（毫秒） */
  const nextDelay = () => {
    if (lastRenderCost <= 0) return 80
    return Math.min(2000, Math.max(80, lastRenderCost * 2))
  }

  /**
   * 排程一次渲染。已有待执行任务时只刷新回调（旧的直接被替换），
   * 保证同一时刻至多一次渲染排队。
   *
   * @param {() => void} fn 渲染函数（读取最新内容并写回 msg.html）
   */
  const schedule = (fn) => {
    pendingFn = fn
    if (timer) return
    timer = setTimeout(() => {
      timer = null
      const run = pendingFn
      pendingFn = null
      if (!run) return
      const start = performance.now()
      try {
        run()
      } finally {
        lastRenderCost = performance.now() - start
      }
    }, nextDelay())
  }

  /** 立即执行待渲染任务并停止排程（流结束/中断/停止生成时调用） */
  const flush = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    const run = pendingFn
    pendingFn = null
    if (run) {
      const start = performance.now()
      try {
        run()
      } finally {
        lastRenderCost = performance.now() - start
      }
    }
  }

  /** 丢弃待渲染任务（组件卸载、切换会话时防泄漏） */
  const dispose = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    pendingFn = null
  }

  return { schedule, flush, dispose }
}
