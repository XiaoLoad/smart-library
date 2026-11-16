<template>
  <div class="ai-page">
    <!-- 会话列表侧栏 -->
    <aside class="conv-side">
      <div class="conv-head">
        <el-button type="primary" :icon="Plus" class="new-btn" @click="newConv">新的对话</el-button>
      </div>
      <div class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === currentConvId }"
          @click="switchConv(c.id)"
        >
          <el-icon class="conv-icon"><ChatDotRound /></el-icon>
          <div class="conv-meta">
            <div class="conv-title">{{ c.title }}</div>
            <div class="conv-time">{{ formatTime(c.createTime) }}</div>
          </div>
          <el-icon class="conv-del" title="删除会话" @click.stop="deleteConv(c.id)">
            <Delete />
          </el-icon>
        </div>
      </div>
    </aside>

    <!-- 对话主区：三段式 flex（头部固定 / 消息滚动 / 输入固定） -->
    <main class="chat-main">
      <header class="chat-head">
        <div class="head-id">
          <span class="eyebrow">智 · AI</span>
          <h2 class="chat-title">智能助手</h2>
        </div>
        <div class="head-tip">
          <el-icon><MagicStick /></el-icon>
          <span>可查询图书、借阅、个人数据</span>
        </div>
      </header>

      <!-- 唯一滚动容器：flex:1 + min-height:0，高度被钉死，输入框永不被顶走 -->
      <div class="msg-scroll" ref="msgListRef" @scroll="onScroll">
        <div class="msg-inner">
          <!-- 欢迎屏 -->
          <div v-if="!messages.length" class="welcome">
            <div class="welcome-mark">智</div>
            <h3 class="welcome-title">您好，请问需要什么帮助？</h3>
            <p class="welcome-desc">我可以为您查询馆藏图书、个人借阅、罚款记录等。</p>
            <div class="quick-prompts">
              <button v-for="p in quickPrompts" :key="p" class="quick-prompt" @click="sendPrompt(p)">
                <span class="quick-dot"></span>{{ p }}
              </button>
            </div>
          </div>

          <template v-for="(m, i) in messages" :key="m.id">
            <!-- 日期分隔线：历史对话的导航锚点 -->
            <div v-if="needDivider(i)" class="day-divider">
              <span class="day-line"></span>
              <span class="day-label">{{ dayLabel(m.createTime) }}</span>
              <span class="day-line"></span>
            </div>

            <!-- 用户消息：右侧气泡（data-anchor 供对话锚点导航定位与判定）。
                 A2：气泡上方分支导航（同轮有多个编辑重发版本时显示 « 2/3 »）；
                 悬停气泡出现操作条（编辑重发 / 重新生成） -->
            <div v-if="m.role === 'user'" class="msg-row user" :data-anchor="String(m.id)">
              <div class="user-col">
                <div v-if="m.branchInfo && m.branchInfo.total > 1" class="branch-nav">
                  <button
                    class="branch-arrow"
                    :disabled="m.branchInfo.index === 0"
                    title="上一版本"
                    @click="switchBranch(m, -1)"
                  >‹</button>
                  <span class="branch-pos">{{ m.branchInfo.index + 1 }}/{{ m.branchInfo.total }}</span>
                  <button
                    class="branch-arrow"
                    :disabled="m.branchInfo.index === m.branchInfo.total - 1"
                    title="下一版本"
                    @click="switchBranch(m, 1)"
                  >›</button>
                </div>
                <div class="user-bubble">{{ m.content }}</div>
                <div class="user-actions">
                  <button class="user-action" title="编辑后重新提问（产生新分支）" @click="startEditResend(m)">编辑</button>
                  <button
                    class="user-action"
                    title="换一个回答（产生新分支）"
                    :disabled="streamingMap.has(currentConvId)"
                    @click="regenerate(m)"
                  >重新生成</button>
                </div>
                <div v-if="m.editing" class="edit-box">
                  <el-input
                    v-model="m.editText"
                    type="textarea"
                    :rows="2"
                    placeholder="编辑提问内容，发送后产生新分支"
                  />
                  <div class="edit-actions">
                    <el-button size="small" @click="m.editing = false">取消</el-button>
                    <el-button size="small" type="primary" :disabled="!m.editText?.trim()" @click="confirmEditResend(m)">
                      发送新分支
                    </el-button>
                  </div>
                </div>
              </div>
            </div>

            <!-- 助手消息：左侧头像 + 空气化排版。
                 @click 事件委托处理正文角标点击（v-html 内容无法直接绑事件） -->
            <div v-else class="msg-row assistant" :data-msg-id="m.id" @click="handleCitationClick(m, $event)">
              <div class="msg-avatar"><span>智</span></div>
              <div class="msg-body">
                <!-- 深度思考（思维链）面板：思考中流式展开，正文开始后自动折叠 -->
                <div
                  v-if="m.reasoningSegments && m.reasoningSegments.length"
                  class="reason-panel"
                >
                  <button class="reason-head" @click="m.reasoningOpen = !m.reasoningOpen">
                    <span class="reason-mark">
                      <span v-if="m.reasoningStreaming" class="reason-dot"></span>
                      <AppIcon v-else name="sparkles" :size="13" />
                    </span>
                    <span class="reason-title">{{ m.reasoningStreaming ? '深度思考中' : '已深度思考' }}</span>
                    <span v-if="m.reasoningSeconds != null" class="reason-time">{{ m.reasoningSeconds }}s</span>
                    <span class="reason-chevron" :class="{ open: m.reasoningOpen }">
                      <AppIcon name="chevron-down" :size="12" />
                    </span>
                  </button>
                  <transition name="reason-expand">
                    <div v-if="m.reasoningOpen" class="reason-body">
                      <template v-for="(seg, si) in m.reasoningSegments" :key="si">
                        <div v-if="si > 0" class="reason-sep"><span></span></div>
                        <div class="reason-seg">{{ seg }}</div>
                      </template>
                    </div>
                  </transition>
                </div>

                <!-- M4：Agent 决策时间线（harness 式折叠汇总）。
                     把「思考旁白 → 工具调用 → 授权请求」按实际发生顺序串成一条链。
                     结束态默认折叠为一行类别计数汇总（「已查询图书 · 已提交借阅 · 12.4s」），
                     点击展开完整链路；运行态保持展开看实时进度。
                     数据全量保留在 timeline 中，折叠只控制可见性 -->
                <div v-if="m.timeline && m.timeline.length" class="timeline-wrap">
                  <!-- 折叠汇总行：仅结束态（非 running）且有 ≥1 个工具节点时显示 -->
                  <button
                    v-if="!m._timelineRunning && m.toolEvents.length > 0"
                    class="timeline-summary"
                    :aria-expanded="m._timelineOpen || undefined"
                    @click="m._timelineOpen = !m._timelineOpen"
                  >
                    <el-icon class="summary-icon"><Connection /></el-icon>
                    <span class="summary-text">{{ timelineSummary(m) }}</span>
                    <span v-if="m.reasoningSeconds != null" class="summary-time">{{ m.reasoningSeconds }}s</span>
                    <AppIcon name="chevron-down" :size="12" class="summary-chevron" :class="{ open: m._timelineOpen }" />
                  </button>

                  <!-- 完整时间线：运行态恒显示；结束态受 _timelineOpen 控制 -->
                  <div
                    v-show="m._timelineRunning || !m.toolEvents.length || m._timelineOpen"
                    class="timeline"
                  >
                  <div v-for="(node, ni) in m.timeline" :key="ni" class="timeline-node" :class="node.kind">
                    <!-- 节点序号：左侧轴线上的圆点标记 -->
                    <span class="timeline-seq">{{ ni + 1 }}</span>

                    <!-- ① 思考旁白：模型的过渡语，斜体弱化 -->
                    <div v-if="node.kind === 'thought'" class="timeline-thought">
                      {{ node.text }}
                    </div>

                    <!-- ② 工具调用：沿用原 pill 卡片形态 -->
                    <div v-else-if="node.kind === 'tool'" class="tool-card" :class="node.card.phase">
                      <div class="tool-head">
                        <el-icon class="tool-icon">
                          <Loading v-if="node.card.phase === 'start'" />
                          <Check v-else-if="node.card.phase === 'end'" />
                          <Close v-else-if="node.card.phase === 'error'" />
                        </el-icon>
                        <span class="tool-name">{{ toolLabel(node.card.name) }}</span>
                        <span v-if="node.card.detail" class="tool-detail">{{ node.card.detail }}</span>
                        <button
                          v-if="node.card.phase === 'end' && node.card.data"
                          class="tool-toggle"
                          :class="{ active: node.card.expanded }"
                          :title="node.card.expanded ? '折叠数据' : '查看返回数据'"
                          @click="node.card.expanded = !node.card.expanded"
                        >
                          <el-icon><ArrowDownBold v-if="!node.card.expanded" /><ArrowUpBold v-else /></el-icon>
                        </button>
                      </div>
                      <!-- M5：生成式 UI —— 按快照 type 渲染成组件（图书卡/排行/额度条/表格/图表），
                           无 type 或未映射时自动回落为 JSON 展开视图 -->
                      <transition name="tool-expand">
                        <div v-if="node.card.expanded && node.card.data" class="tool-data gen-ui-wrap">
                          <AiGenUi :snapshot="parseSnapshot(node.card.data)" @detail="openBookDetail" @borrow="borrowFromCard" />
                        </div>
                      </transition>
                    </div>

                    <!-- ③ 授权请求：指向下方授权卡（卡片本身在时间线之后独立渲染） -->
                    <div v-else-if="node.kind === 'confirm'" class="timeline-confirm">
                      <el-icon><Lock /></el-icon>
                      <span>等待你的授权确认</span>
                    </div>
                  </div>
                  </div>
                </div>

                <!-- M5 升级：生成式 UI 一级内联卡片。
                     带可视化 type 的工具快照不再折叠在工具 pill 里，直接平铺渲染在时间线之后，
                     默认展开——用户无需上滑点击工具条展开；工具 pill 仍可查看原始 JSON（调试用）。
                     纯 JSON 回落快照（无 type 映射）不在此渲染，保持原折叠行为 -->
                <div
                  v-for="(card, gi) in inlineGenCards(m)"
                  :key="'gi-' + gi"
                  class="gen-ui-inline"
                  :data-gen-card="genCardAnchorId(m, gi)"
                  :data-gen-turn="card._turnMarker"
                >
                  <div class="gen-ui-inline-label">
                    <el-icon><DataBoard /></el-icon>
                    <span>{{ genCardTitle(card) }}</span>
                    <span v-if="genCardArgLabel(card)" class="gen-ui-args" :title="'检索参数：' + genCardArgLabel(card)">
                      {{ genCardArgLabel(card) }}
                    </span>
                    <span v-if="card._mergeCount > 1" class="gen-ui-merged">已合并 {{ card._mergeCount }} 次检索</span>
                    <span v-if="card._fromHistory" class="gen-ui-time" :title="'查询时点：' + formatSnapTime(card._snapshotTime)">
                      {{ formatSnapTime(card._snapshotTime) }} 查询
                    </span>
                  </div>
                  <AiGenUi :snapshot="parseSnapshot(card._mergedData || card.data)" @detail="openBookDetail" @borrow="borrowFromCard" />
                </div>

                <!-- 写操作授权卡：AI 发起的写操作被代码级门禁拦截，等待用户授权。
                     形态刻意区别于工具卡片（卡片 vs pill），避免被当成一次普通查询结果。
                     M10：confirmCards 为数组——同一轮模型可连续发起多个写操作，
                     每个写操作一张卡，互不覆盖 -->
                <div
                  v-for="(card, ci) in m.confirmCards || []"
                  :key="'cc-' + ci"
                  class="confirm-card"
                  :class="card.state"
                >
                  <div class="confirm-head">
                    <span class="confirm-eyebrow">
                      <el-icon><Lock /></el-icon>
                      <span>{{ confirmEyebrow(card.state) }}</span>
                    </span>
                  </div>
                  <div class="confirm-title">{{ card.title }}</div>
                  <div v-if="card.rows && card.rows.length" class="confirm-rows">
                    <div v-for="(r, ri) in card.rows" :key="ri" class="confirm-row">
                      <span class="confirm-k">{{ r.key }}</span>
                      <span class="confirm-v mono">{{ r.value }}</span>
                    </div>
                  </div>
                  <div v-if="card.state === 'pending'" class="confirm-actions">
                    <label class="confirm-allow" title="勾选后本会话内同类写操作不再弹卡，直接执行">
                      <input
                        v-model="card.alwaysAllow"
                        type="checkbox"
                        class="confirm-allow-box"
                      />
                      <span>本会话始终允许此类操作</span>
                    </label>
                    <button class="confirm-btn primary" @click="onConfirmWrite(card)">
                      确认办理
                    </button>
                    <button class="confirm-btn" @click="onCancelWrite(card)">取消</button>
                    <span class="confirm-ttl">{{ card.remain ?? card.ttlSeconds }} 秒内有效</span>
                  </div>
                  <div v-else-if="card.state === 'submitting'" class="confirm-actions">
                    <span class="confirm-state">
                      <el-icon class="is-loading"><Loading /></el-icon>
                      <span>正在办理…</span>
                    </span>
                  </div>
                  <div v-else-if="card.state === 'confirmed'" class="confirm-actions confirm-batch-result">
                    <span class="confirm-state ok">
                      <el-icon><Check /></el-icon>
                      <span>{{ card.result?.message || '已确认并执行' }}</span>
                    </span>
                    <!-- M10：逐项结果内联在卡片上（批量办理/多记录操作），替代回执文字 -->
                    <div v-if="card.result?.results?.length" class="batch-result-list">
                      <div
                        v-for="(r, ri) in card.result.results"
                        :key="ri"
                        class="batch-result-item"
                        :class="{ fail: r.success === false }"
                      >
                        <span class="batch-result-title">{{ r.borrowNo || r.title || r.tool }}</span>
                        <span class="batch-result-msg">{{ r.message || (r.success ? '成功' : '失败') }}</span>
                      </div>
                    </div>
                  </div>
                  <div v-else-if="card.state === 'cancelled'" class="confirm-actions">
                    <span class="confirm-state muted">
                      <el-icon><Close /></el-icon>
                      <span>已取消本次操作</span>
                    </span>
                  </div>
                  <div v-else-if="card.state === 'expired'" class="confirm-actions">
                    <span class="confirm-state err">
                      <el-icon><Warning /></el-icon>
                      <span>{{ card.error || '授权已失效，请重新发起' }}</span>
                    </span>
                  </div>
                </div>

                <!-- M7 批量授权卡（batch 档位）：本轮多个写操作合并一张卡，一次确认整批执行。
                     形态与单笔授权卡一致，eyebrow 标注批量属性与操作数 -->
                <div v-if="m.batchConfirmCard" class="confirm-card batch" :class="m.batchConfirmCard.state">
                  <div class="confirm-head">
                    <span class="confirm-eyebrow">
                      <el-icon><Lock /></el-icon>
                      <span>{{ batchEyebrow(m.batchConfirmCard) }}</span>
                    </span>
                  </div>
                  <div class="confirm-title">{{ m.batchConfirmCard.title }}</div>
                  <div v-if="m.batchConfirmCard.rows && m.batchConfirmCard.rows.length" class="confirm-rows">
                    <div v-for="(r, ri) in m.batchConfirmCard.rows" :key="ri" class="confirm-row">
                      <span class="confirm-k">{{ r.key }}</span>
                      <span class="confirm-v mono">{{ r.value }}</span>
                    </div>
                  </div>
                  <div v-if="m.batchConfirmCard.state === 'pending'" class="confirm-actions">
                    <button class="confirm-btn primary" @click="onConfirmBatch(m.batchConfirmCard)">
                      全部确认（{{ m.batchConfirmCard.count }} 项）
                    </button>
                    <button class="confirm-btn" @click="onCancelBatch(m.batchConfirmCard)">取消</button>
                    <span class="confirm-ttl">{{ m.batchConfirmCard.remain ?? m.batchConfirmCard.ttlSeconds }} 秒内有效</span>
                  </div>
                  <div v-else-if="m.batchConfirmCard.state === 'submitting'" class="confirm-actions">
                    <span class="confirm-state">
                      <el-icon class="is-loading"><Loading /></el-icon>
                      <span>正在批量办理…</span>
                    </span>
                  </div>
                  <div v-else-if="m.batchConfirmCard.state === 'confirmed'" class="confirm-actions confirm-batch-result">
                    <span class="confirm-state ok">
                      <el-icon><Check /></el-icon>
                      <span>{{ m.batchConfirmCard.result?.message || '批量办理完成' }}</span>
                    </span>
                    <div v-if="m.batchConfirmCard.result?.results?.length" class="batch-result-list">
                      <div
                        v-for="(r, ri) in m.batchConfirmCard.result.results"
                        :key="ri"
                        class="batch-result-item"
                        :class="{ fail: r.success === false }"
                      >
                        <span class="batch-result-title">{{ r.title || r.tool }}</span>
                        <span class="batch-result-msg">{{ r.message || (r.success ? '成功' : '失败') }}</span>
                      </div>
                    </div>
                  </div>
                  <div v-else-if="m.batchConfirmCard.state === 'cancelled'" class="confirm-actions">
                    <span class="confirm-state muted">
                      <el-icon><Close /></el-icon>
                      <span>已取消本批操作</span>
                    </span>
                  </div>
                  <div v-else-if="m.batchConfirmCard.state === 'expired' || m.batchConfirmCard.state === 'failed'" class="confirm-actions">
                    <span class="confirm-state err">
                      <el-icon><Warning /></el-icon>
                      <span>{{ m.batchConfirmCard.error || '授权已失效，请重新发起' }}</span>
                    </span>
                  </div>
                </div>

                <!-- M9 历史授权卡（只读）：决策态落库后，刷新/切会话回来仍能看到
                     当时的授权卡与决策结果；无按钮，纯展示 -->
                <div
                  v-for="(hc, hi) in m.historyCards || []"
                  :key="'hc-' + hi"
                  class="confirm-card history"
                  :class="hc.state"
                >
                  <div class="confirm-head">
                    <span class="confirm-eyebrow">
                      <el-icon><Lock /></el-icon>
                      <span>{{ historyCardEyebrow(hc) }}</span>
                    </span>
                    <span class="confirm-ttl">{{ formatTime(hc.createTime) }}</span>
                  </div>
                  <div class="confirm-title">{{ hc.title }}</div>
                  <div v-if="hc.rows && hc.rows.length" class="confirm-rows">
                    <div v-for="(r, ri) in hc.rows" :key="ri" class="confirm-row">
                      <span class="confirm-k">{{ r.key }}</span>
                      <span class="confirm-v mono">{{ r.value }}</span>
                    </div>
                  </div>
                </div>

                <!-- 参考资料：本轮注入的知识库来源。
                     后端 rag 事件在调用 LLM 之前推送（早于 delta），故参考资料条先出、正文随后流式出现；
                     点击药丸展开片段摘要（后端只回传截断摘要，不落库，历史消息无此块） -->
                <div v-if="m.ragSources && m.ragSources.length" class="rag-block">
                  <div class="rag-row">
                    <span class="rag-label">
                      <AppIcon name="knowledge" :size="13" />
                      <span>参考资料</span>
                    </span>
                    <button
                      v-for="(s, si) in m.ragSources"
                      :key="si"
                      class="rag-chip"
                      :class="{ active: m.ragOpenIndex === si }"
                      :title="m.ragOpenIndex === si ? '收起片段' : '查看片段摘要'"
                      @click="m.ragOpenIndex = m.ragOpenIndex === si ? -1 : si"
                    >
                      <AppIcon name="book" :size="12" />
                      <span class="rag-title">{{ s.docTitle }}</span>
                    </button>
                  </div>
                  <transition name="tool-expand">
                    <div v-if="m.ragOpenIndex >= 0 && m.ragSources[m.ragOpenIndex]" class="rag-snippet">
                      <div class="tool-data-label">
                        <AppIcon name="knowledge" :size="12" />
                        <span>{{ m.ragSources[m.ragOpenIndex].docTitle }} · 检索片段摘要</span>
                      </div>
                      <p>{{ m.ragSources[m.ragOpenIndex].snippet }}</p>
                    </div>
                  </transition>
                </div>

                <div class="msg-content markdown" v-html="m.html"></div>

                <!-- 建议追问：最新回答下方的 ghost 灰字（仅本轮 done 后拉取的最新回答持有），
                     点击直接作为新消息发送。流式期间/发送新消息后不显示 -->
                <div v-if="m.followUps?.length && !m.pending" class="followup-chips">
                  <button
                    v-for="(s, i) in m.followUps"
                    :key="i"
                    class="followup-chip"
                    type="button"
                    @click="sendFollowUp(s)"
                  >
                    {{ s }}
                  </button>
                </div>

                <!-- 降级提示：工具循环超限时的优雅收尾（非报错样式）。
                     说明已完成部分仍然有效，引导用户就未完成部分继续追问 -->
                <div v-if="m.degraded" class="degrade-tip">
                  <AppIcon name="knowledge" :size="13" />
                  <span>{{ m.degradeMessage }}</span>
                </div>

                <!-- 等待中：已建消息占位但尚无任何可见产出（思考未开始/检索中/工具间隙），
                     点状跳动提示避免「参考资料之后就没动静」的空窗；有正文或思考流后立即消失 -->
                <div
                  v-if="m.pending && !m.reasoningSegments.length && !m.toolEvents.length && !(m.ragSources && m.ragSources.length)"
                  class="msg-content thinking"
                >
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                  <span class="thinking-text">正在思考…</span>
                </div>
                <div
                  v-else-if="m.pending && (m.ragSources && m.ragSources.length) && !m.reasoningSegments.length && !m.toolEvents.length && !m.content"
                  class="msg-content thinking"
                >
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                  <span class="thinking-text">正在结合参考资料思考…</span>
                </div>
              </div>
            </div>
          </template>

          <!-- 排队气泡：本会话流未结束时发送的消息先排队，轮到时自动发出（chat-demo 同款）。
               单条展示即可——每会话队列 FIFO，多条排队时重复的提示没有信息量 -->
          <div v-if="queuedForCurrent" class="msg-row user">
            <div class="user-bubble queued">
              {{ pendingQueue.find((q) => q.convId === currentConvId)?.content }}
              <span class="queued-tag">排队中 · 上一轮回复结束后自动发送</span>
            </div>
          </div>

          <!-- 底部留白，保证最后一条不被输入框遮挡 -->
          <div class="msg-bottom-gap"></div>
        </div>
      </div>

      <!-- 对话锚点导航：按提问轮次生成刻度，悬停看完整问题，点击定位 -->
      <ConversationNav
        :items="anchors"
        :scroll-el="msgListRef"
        :at-bottom="isAutoFollow"
        @jump="onJumpAnchor"
      />

      <!-- 「回到最新」浮动按钮：锚定在输入框上方，不随内容滚动 -->
      <transition name="jump">
        <button v-if="showJumpBtn" class="jump-latest" @click="scrollToBottomForce">
          <el-icon><ArrowDownBold /></el-icon>
          回到最新
        </button>
      </transition>

      <!-- 输入区：固定在视口底部 -->
      <div class="composer">
        <!-- M7 写操作权限档位：confirm=逐笔确认 / batch=批量授权 / auto=自动执行（仅管理员可切）。
             切换即时生效并持久到会话；无会话时显示默认档，首次切换/发送时自动建会话 -->
        <div class="perm-mode-bar">
          <span class="perm-label">写操作权限</span>
          <div class="perm-seg" :class="{ danger: permMode === 'auto' }">
            <button
              v-for="opt in permOptions"
              :key="opt.value"
              class="perm-opt"
              :class="{ active: permMode === opt.value }"
              :title="opt.title"
              :disabled="opt.adminOnly && !isAdminUser"
              @click="switchPermMode(opt.value)"
            >{{ opt.label }}</button>
          </div>
          <!-- auto 档持续警示（ZCode 同款思路：高权限模式下风险提示常驻） -->
          <span v-if="permMode === 'auto'" class="perm-warn">
            <el-icon><Warning /></el-icon>
            自动执行中：写操作无需确认直接生效
          </span>
        </div>
        <div class="composer-box" :class="{ active: input, blocked: hasPendingConfirm, 'has-mic': asrAvailable }">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            :placeholder="hasPendingConfirm ? '有待确认的授权请求，请先在上方卡片上确认或取消' : '输入您的问题，Enter 发送，Shift+Enter 换行'"
            resize="none"
            class="composer-input"
            @keydown.enter.exact.prevent="send"
          />
          <!-- 语音输入：WebAudio 录 WAV → 后端中转云端 ASR（渠道见系统设置），未配置时隐藏 -->
          <button
            v-if="asrAvailable"
            class="composer-btn mic"
            :class="{ recording: asrRecording }"
            :disabled="asrTranscribing"
            :title="asrTranscribing ? '正在转写…' : asrRecording ? `停止并转写（${asrRecordingSec}s）` : '语音输入'"
            @click="toggleVoiceInput"
          >
            <el-icon><Loading v-if="asrTranscribing" class="is-loading" /><Microphone v-else /></el-icon>
          </button>
          <button
            class="composer-btn"
            :class="currentStreaming ? 'stop' : 'send'"
            :title="currentStreaming ? '停止生成' : '发送'"
            @click="currentStreaming ? stopGenerate() : send()"
          >
            <el-icon><VideoPause v-if="currentStreaming" /><Promotion v-else /></el-icon>
          </button>
        </div>
        <p v-if="hasPendingConfirm" class="composer-tip composer-blocked-tip">
          <el-icon><Lock /></el-icon>
          授权请求等待处理中：全部确认或取消后才能继续对话
        </p>
        <p v-else-if="asrRecording" class="composer-tip asr-tip">
          <el-icon><Microphone /></el-icon>
          正在录音 {{ asrRecordingSec }}s，再次点击麦克风结束并转写，最长 120 秒
        </p>
        <p v-else class="composer-tip">AI 助手基于馆藏数据回答，内容仅供参考。</p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus, ChatDotRound, MagicStick, Promotion, Delete,
  VideoPause, Loading, Check, Close, ArrowDownBold, ArrowUpBold, Connection,
  Lock, Warning, DataBoard, Microphone,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi, asrApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ConversationNav from '@/components/ConversationNav.vue'
import AppIcon from '@/components/AppIcon.vue'
import AiGenUi from '@/components/ai/AiGenUi.vue'
import { createStreamRenderer } from '@/utils/streamRender'
import { renderMarkdown, renderMarkdownStream, handleCodeCopyClick } from '@/utils/markdown'
import { classifyAiError, describeWithAction, withRetry, AiErrorKind } from '@/utils/aiErrors'
import { MicRecorder } from '@/utils/recorder'

// keep-alive 缓存名单按路由 name 匹配，故显式声明组件名
defineOptions({ name: 'AIChat' })

// 工具名称中文映射
const TOOL_LABELS = {
  // 通用工具（读者 + 管理员）
  queryCategories: '查询图书分类',
  queryBooks: '检索馆藏图书',
  queryHotBooks: '热门图书排行',
  queryAnnouncements: '查询公告',
  // 读者工具
  queryMyBorrows: '查询借阅记录',
  queryMyFines: '查询罚款记录',
  queryBorrowQuota: '查询借阅额度',
  queryMyNotifications: '查询通知消息',
  applyBorrow: '提交借阅申请',
  renewMyBorrow: '续借图书',
  requestReturn: '申请归还图书',
  // 管理员工具
  adminQueryBorrows: '查询全馆借阅',
  adminQueryFines: '查询全馆罚款',
  adminQueryUsers: '查询读者信息',
  adminQueryStats: '馆藏统计概览',
  adminCounterProcess: '柜台办理业务',
}
const toolLabel = (name) => TOOL_LABELS[name] || name

/**
 * 授权卡的眉标文案：随状态切换，让用户一眼看出「还需要我操作」还是「已经处理完」。
 */
const CONFIRM_EYEBROWS = {
  pending: '需要你的授权',
  submitting: '正在办理',
  confirmed: '已确认',
  cancelled: '已取消',
  expired: '授权失效',
  failed: '办理失败',
}
const confirmEyebrow = (state) => CONFIRM_EYEBROWS[state] || '需要你的授权'

/**
 * 格式化工具返回的 JSON 数据快照用于展示。
 * 后端推送的 data 已是 JSON 字符串，这里尝试解析后美化输出，失败则原样返回。
 * @param {string} dataJson 后端推送的 JSON 字符串（可能已截断带省略号）
 */
const formatJson = (dataJson) => {
  if (!dataJson) return ''
  try {
    // 截断快照末尾的 "…" 需先去除再解析
    const trimmed = dataJson.replace(/…$/, '')
    const obj = JSON.parse(trimmed)
    return JSON.stringify(obj, null, 2)
  } catch {
    // 解析失败（如已截断）原样返回
    return dataJson
  }
}

/**
 * M5：把工具快照 JSON 字符串解析为对象，供生成式 UI 组件按 type 渲染。
 * 解析失败（含被截断的快照）返回 null，组件自动回落到 JSON 展示。
 */
const parseSnapshot = (dataJson) => {
  if (!dataJson || typeof dataJson !== 'string') return null
  try {
    // 截断快照末尾的 "…" 需先去除再解析
    return JSON.parse(dataJson.replace(/…$/, ''))
  } catch {
    return null
  }
}

// ===== 生成式 UI 一级内联卡片 =====
// 可视化 type → 卡片标题（与 AiGenUi 内各视图对应）
const GEN_CARD_TITLES = {
  book_grid: '图书卡片',
  rank_list: '排行榜',
  quota_bar: '借阅额度',
  category_grid: '分类分布',
  announce_list: '公告列表',
  borrow_table: '借阅记录',
  fine_table: '罚款记录',
  user_table: '用户列表',
  stats_dashboard: '统计看板',
}

/**
 * 取本轮工具事件中带可视化 type 的快照（内联渲染用）。
 * 每个事件对象记有 _inlineGen 标志（tool end 时算好），避免模板里反复 JSON.parse。
 */
const inlineGenCards = (m) =>
  (m.toolEvents || []).filter((e) => e.phase === 'end' && e._inlineGen)

const genCardTitle = (card) =>
  GEN_CARD_TITLES[card._genType] || '数据卡片'

// —— M4 时间线折叠汇总（harness 式：结束后折叠为类别计数一行）——

/** 工具名 → 类别标签（计数汇总用；比 TOOL_LABELS 更粗粒度，同类合并） */
const TOOL_KINDS = {
  queryCategories: '查询分类',
  queryBooks: '查询图书',
  queryHotBooks: '查询排行',
  queryAnnouncements: '查询公告',
  queryMyBorrows: '查询借阅',
  queryMyFines: '查询罚款',
  queryBorrowQuota: '查询额度',
  queryMyNotifications: '查询通知',
  applyBorrow: '提交借阅',
  renewMyBorrow: '办理续借',
  requestReturn: '办理归还',
  adminQueryBorrows: '查询借阅',
  adminQueryFines: '查询罚款',
  adminQueryUsers: '查询读者',
  adminQueryStats: '统计概览',
  adminCounterProcess: '柜台办理',
}
const toolKind = (name) => TOOL_KINDS[name] || toolLabel(name)

/**
 * 结束态时间线的折叠汇总标题：按工具类别计数排名（前 3），
 * 形如「查询图书 ×2 · 提交借阅 ×1 · 柜台办理 ×1」。零工具时回退「已完成处理」。
 */
const timelineSummary = (m) => {
  const counts = new Map()
  for (const t of m.toolEvents || []) {
    if (t.phase !== 'end') continue
    const kind = toolKind(t.name)
    counts.set(kind, (counts.get(kind) || 0) + 1)
  }
  if (!counts.size) return '已完成处理'
  const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3)
  return ranked.map(([kind, n]) => (n > 1 ? `${kind} ×${n}` : kind)).join(' · ')
}

/** 快照时点展示（历史卡片角标）：今天显示时分，更早显示 月-日 时:分 */
const formatSnapTime = (t) => {
  if (!t) return '历史'
  const d = new Date(t)
  if (isNaN(d.getTime())) return '历史'
  const pad = (n) => String(n).padStart(2, '0')
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}-${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// DOM 锚点 id：done 后滚动定位第一张内联卡片用（历史消息无流式 id，用消息序号）
const genCardAnchorId = (m, gi) => `gen-card-${m._seq ?? m.id ?? 'x'}-${gi}`

/**
 * 判定工具快照是否带可视化 type（与 AiGenUi 支持的映射一致），
 * 是则打 _inlineGen 标志提升为一级内联卡片；纯 JSON 回落快照保持折叠。
 */
const GEN_UI_TYPES = new Set(Object.keys(GEN_CARD_TITLES))
const markInlineGen = (card) => {
  try {
    const snap = JSON.parse((card.data || '').replace(/…$/, ''))
    if (snap && GEN_UI_TYPES.has(snap.type)) {
      card._inlineGen = true
      card._genType = snap.type
    }
  } catch { /* 截断/异常快照回落原折叠行为 */ }
}

/**
 * done 后滚动定位：本轮有内联生成式卡片时滚到第一张卡片（而非正文底部），
 * 保证卡片直接进入视野；无卡片维持原有滚底行为。
 * 通过 data-gen-turn 标记精确定位本轮消息的卡片——不能全局查 .gen-ui-inline，
 * 会话历史里可能有更早的卡片，querySelector 会命中它们导致滚错位置。
 */
const scrollToFirstGenCard = async (msg) => {
  await nextTick()
  if (!msg) return false
  const marker = `gen-turn-${msg.id}`
  msg.toolEvents?.forEach((e) => { if (e._inlineGen) e._turnMarker = marker })
  await nextTick()
  const first = document.querySelector(`[data-gen-turn="${marker}"]`)
  if (first) {
    first.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return true
  }
  return false
}

/**
 * 角标 / 代码块复制按钮点击（事件委托，v-html 内容无法直接绑事件）：
 * 先处理复制按钮（命中则结束），再处理引用角标——展开/收起对应编号的片段摘要
 */
const handleCitationClick = (m, e) => {
  if (handleCodeCopyClick(e)) return
  const badge = e.target.closest('.cite-badge')
  if (!badge) return
  const n = Number(badge.dataset.src)
  if (!m.ragSources?.some((s) => s.sourceNo === n)) return
  m.ragOpenIndex = m.ragOpenIndex === n - 1 ? -1 : n - 1
  if (m.ragOpenIndex >= 0) {
    nextTick(() => {
      document.querySelector(`.msg-bubble[data-msg-id="${m.id}"] .rag-snippet`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    })
  }
}

// 历史/分页消息映射：预渲染 Markdown 为 html 缓存，避免模板内反复重渲染
const enrichMsg = (m) => ({
  ...m,
  _fromHistory: true, // A2：历史消息标志——分支导航/编辑重发只对服务端已落库的消息开放
  html: renderMarkdown(m.content),
  // 工具快照落库后历史消息可重建内联生成式卡片；快照是查询时点数据，历史卡片带时点角标
  toolEvents: parseStoredSnapshots(m.toolSnapshots),
  // M9：历史授权卡（快照通道里的 card_decision 记录）重建为只读卡片
  historyCards: parseStoredCardDecisions(m.toolSnapshots),
  // 历史消息不持久化思维链（与工具中间态一致），仅本轮流式生成时展示
  reasoningSegments: [],
  reasoningOpen: false,
  reasoningStreaming: false,
  // 参考资料同理：rag 事件只在当轮推送，落库内容不含来源，历史消息无此块
  ragSources: [],
  ragOpenIndex: -1,
  // M4：thought 旁白不落库（与思维链一致），历史消息无时间线
  timeline: [],
  // A2：分支状态占位（branchInfo 由 loadBranchInfo 异步填充；本地流式消息无此字段）
  branchInfo: null,
})

// —— A2 消息分支 ——

/** 拉取一轮的兄弟分支并写入消息的 branchInfo（含在兄弟列表中的位置）。仅历史消息有真实 id */
const loadBranchInfo = async (m) => {
  if (!m || m.role !== 'user' || !m._fromHistory) return
  try {
    const res = await aiApi.branchSiblings(currentConvId.value, m.id)
    const list = res?.list || []
    if (!list.length) return
    const idx = list.findIndex((s) => s.id === m.id)
    m.branchInfo = {
      groupKey: res.groupKey,
      total: list.length,
      index: idx >= 0 ? idx : 0,
    }
  } catch {
    // 分支查询失败静默：导航不显示，不影响对话
  }
}

/** 切换分支：切到当前消息的相邻兄弟版本（delta = -1 上一版 / 1 下一版），服务端重设激活链后重拉历史 */
const switchBranch = async (m, delta) => {
  if (!m.branchInfo || currentConvId.value === null) return
  try {
    const res = await aiApi.branchSiblings(currentConvId.value, m.id)
    const list = res?.list || []
    const idx = list.findIndex((s) => s.id === m.id)
    const target = list[idx + delta]
    if (!target) return
    await aiApi.switchBranch(currentConvId.value, target.id, m.branchInfo.groupKey)
    // 激活链已变：清缓存重拉（convStates 里该会话的快照已过期）
    const st = stateOf(currentConvId.value)
    st.messages = []
    st.totalMessages = 0
    await switchConv(currentConvId.value)
  } catch (e) {
    ElMessage.error(e?.message || '分支切换失败')
  }
}

/** 编辑重发：展开编辑框（不覆盖原消息，发送后产生新分支） */
const startEditResend = (m) => {
  m.editing = true
  m.editText = m.content
}

/** 确认编辑重发：以原消息的分支组发起新分支轮 */
const confirmEditResend = async (m) => {
  const content = m.editText?.trim()
  if (!content || currentConvId.value === null) return
  m.editing = false
  // 分支组可能尚未建立（历史首轮无 group_key）：先查一次触发服务端懒初始化
  let groupKey = m.branchInfo?.groupKey
  if (!groupKey) {
    try {
      const res = await aiApi.branchSiblings(currentConvId.value, m.id)
      groupKey = res?.groupKey
    } catch { /* 拿不到组则按普通消息发送 */ }
  }
  m.branchInfo = null // 落库后重拉，先清掉避免过期的 index/total
  await sendInternal(content, { continuation: false, branchGroupKey: groupKey || undefined })
}

/** 重新生成：同一提问换一个回答（新分支），仅历史消息可用（流式中的消息 id 是本地临时 id） */
const regenerate = async (m) => {
  if (currentConvId.value === null) return
  // 找到本轮的 user 消息（助手消息向前找最近的 user）
  const idx = messages.value.indexOf(m)
  let userMsg = null
  for (let i = Math.max(idx, 0); i >= 0; i--) {
    if (messages.value[i].role === 'user') { userMsg = messages.value[i]; break }
  }
  if (!userMsg || !userMsg._fromHistory) return
  let groupKey = userMsg.branchInfo?.groupKey
  if (!groupKey) {
    try {
      const res = await aiApi.branchSiblings(currentConvId.value, userMsg.id)
      groupKey = res?.groupKey
      if (userMsg.branchInfo) userMsg.branchInfo.groupKey = groupKey
    } catch { /* 拿不到组则按普通轮重发 */ }
  }
  await sendInternal(userMsg.content, { continuation: false, branchGroupKey: groupKey || undefined })
}

/**
 * M9：从快照通道解析授权卡决策记录为只读历史卡片。
 * 与查询快照（toolEvents）同通道但 kind=card_decision，互不混淆。
 */
const parseStoredCardDecisions = (snapshotsJson) => {
  if (!snapshotsJson || typeof snapshotsJson !== 'string') return []
  try {
    const arr = JSON.parse(snapshotsJson)
    if (!Array.isArray(arr)) return []
    return arr
      .filter((rec) => rec && rec.kind === 'card_decision')
      .map((rec) => ({
        tool: rec.tool || '',
        title: rec.title || '',
        rows: Array.isArray(rec.rows) ? rec.rows : [],
        state: rec.state || 'expired',
        batch: rec.batch === true,
        createTime: rec.createTime || '',
      }))
  } catch {
    return []
  }
}

/**
 * 解析落库的工具快照数组为 toolEvents 形态（供内联卡片渲染）。
 * 历史卡片标记 _fromHistory（时点角标判定）；快照为落库时点数据，不参与检索联动。
 */
const parseStoredSnapshots = (snapshotsJson) => {
  if (!snapshotsJson || typeof snapshotsJson !== 'string') return []
  try {
    const arr = JSON.parse(snapshotsJson)
    if (!Array.isArray(arr)) return []
    return arr.map((rec) => ({
      name: rec.tool || '',
      phase: 'end',
      detail: '',
      data: rec.data ? JSON.stringify(rec.data) : null,
      _inlineGen: true,
      _genType: rec.type || '',
      _fromHistory: true,
      _snapshotTime: rec.createTime || '',
      expanded: false,
    }))
  } catch {
    return []
  }
}

// 日期分隔（历史导航锚点）
const dayKey = (t) => (t ? String(t).slice(0, 10) : '')
const dayLabel = (t) => {
  const key = dayKey(t)
  if (!key) return ''
  const today = new Date()
  const fmt = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const yest = new Date(today); yest.setDate(today.getDate() - 1)
  if (key === fmt(today)) return '今天'
  if (key === fmt(yest)) return '昨天'
  const [y, mo, d] = key.split('-').map(Number)
  return y === today.getFullYear() ? `${mo}月${d}日` : `${y}年${mo}月${d}日`
}
// 与上一条消息不同日（或为第一条）时显示分隔线
const needDivider = (i) => {
  if (i === 0) return true
  return dayKey(messages.value[i].createTime) !== dayKey(messages.value[i - 1].createTime)
}

const conversations = ref([])
const currentConvId = ref(null)
// —— 多会话并行支持（对齐 deepseek-ai/chat-demo 的 per-session streaming 模型）——
// convStates：按会话缓存消息与分页状态。切走再切回时内存态即时恢复（不等接口），
// 并行流的增量写入各自会话的缓存，切换不打断任何一路流。
// { messages: [...], loadedCount, totalMessages, oldestLoadedId, hasMore }
const convStates = reactive(new Map())
// 当前会话投影：模板与既有逻辑继续用 messages/loadedCount 等 ref，读写都落到 convStates
const messages = computed(() => {
  const st = convStates.get(currentConvId.value)
  return st ? st.messages : []
})
const loadedCount = computed(() => convStates.get(currentConvId.value)?.loadedCount ?? 0)
const totalMessages = computed(() => convStates.get(currentConvId.value)?.totalMessages ?? 0)
const oldestLoadedId = computed(() => convStates.get(currentConvId.value)?.oldestLoadedId ?? null)
const hasMore = computed(() => convStates.get(currentConvId.value)?.hasMore ?? false)

/** 取（或创建）会话状态条目 */
const stateOf = (convId) => {
  let st = convStates.get(convId)
  if (!st) {
    st = reactive({ messages: [], loadedCount: 0, totalMessages: 0, oldestLoadedId: null, hasMore: false })
    convStates.set(convId, st)
  }
  return st
}

const input = ref('')
// streamingMap：每会话一路独立流（对齐 chat-demo 的 streaming: Record<sessionId, AbortController>）。
// key = 会话 ID，value = 该会话当前流的 AbortController；按钮与守卫按「当前会话是否在流中」判定，
// 其他会话的流不影响当前输入区
const streamingMap = reactive(new Map())
// 当前会话是否正在流式回复（派生）：控制停止/发送按钮形态与切会话守卫
const currentStreaming = computed(() => currentConvId.value !== null && streamingMap.has(currentConvId.value))
// 排队中的待发消息（每会话一条队列）：当前会话流未结束时发送 → 先入队，
// done/error/停止后自动发出。对齐 chat-demo「发送排队等待上一轮结束再自动发送」的行为
// { convId, content }；排队气泡挂在对应会话消息列表尾部
const pendingQueue = reactive([])
// 排队气泡展示用：当前会话是否存在排队消息
const queuedForCurrent = computed(() => pendingQueue.some((q) => q.convId === currentConvId.value))

const msgListRef = ref(null)

// A1/A6：流式渲染与中断判定所需的模块级上下文（每路流一组，随流结束置 null）。
// 旧实现为单流单实例；多会话并行后按 convId 存取，handleEvent 等闭包经 getStreamCtx 取用
const streamCtxMap = new Map() // convId → { abortController, streamRenderer, doneReceived, assistantMsg, ... }
const getStreamCtx = (convId) => streamCtxMap.get(convId)

// 思考占位指示（「正在思考…」点状跳动）：按会话记录。首个流事件到达即关闭（单流语义不变），
// 按钮形态不再挂在此处——由 currentStreaming 控制
const thinkingMap = reactive(new Map())
const thinking = computed(() => currentConvId.value !== null && !!thinkingMap.get(currentConvId.value))

// M7 写操作权限档位：随会话切换加载，切换时即时调 API 持久到会话
const permMode = ref('confirm')
// 管理员身份：user store 里的 userType（3=管理员）
const userStore = useUserStore()
const isAdminUser = userStore.isAdmin
const permOptions = [
  { value: 'confirm', label: '逐笔确认', title: '每个写操作执行前都弹授权卡（默认）', adminOnly: false },
  { value: 'batch', label: '批量授权', title: '本轮写操作合并一张卡，一次确认整批', adminOnly: false },
  { value: 'auto', label: '自动执行', title: '写操作直接执行，仅展示回执（仅管理员）', adminOnly: true },
]
const switchPermMode = async (mode) => {
  if (mode === permMode.value) return
  // 无会话时先创建：档位是会话级属性，切换必须落到具体会话上
  if (!currentConvId.value) {
    try {
      const conv = await aiApi.createConversation()
      conversations.value.unshift(conv)
      currentConvId.value = conv.id
    } catch (e) {
      ElMessage.error('创建会话失败')
      return
    }
  }
  try {
    await aiApi.setPermissionMode(currentConvId.value, mode)
    permMode.value = mode
    ElMessage.success(mode === 'confirm' ? '已切换为逐笔确认' : mode === 'batch' ? '已切换为批量授权' : '已切换为自动执行')
  } catch (e) {
    ElMessage.error(e?.message || '权限档位切换失败')
  }
}

// AI 检索联动：已收窄到悬浮球面板——AI 助手页自身有完整检索展示（生成式卡片），
// 查询结果只留在对话内，不再写入 aiSearchStore 跨页投递
const lastUserText = ref('')
// 历史分页加载状态（分页字段已并入 convStates 投影，见声明区；此处仅保留拉取守卫）
const loadingOlder = ref(false)    // 防重复拉取守卫

// 智能滚动控制
const isAutoFollow = ref(true)
const showJumpBtn = ref(false)

// 程序滚动抑制窗口：代码赋值 scrollTop 触发的 scroll 事件回调执行时，
// DOM 可能又增高（流式渲染），会被误判为"用户上翻中"而关闭自动跟随。
// 赋值前打时间戳，窗口内的 scroll 事件不参与跟随判定。
let programmaticScrollUntil = 0
const suppressScrollUntil = () => {
  programmaticScrollUntil = performance.now() + 100
}

const isAtBottom = () => {
  const el = msgListRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

const onScroll = () => {
  if (performance.now() < programmaticScrollUntil) return // 程序滚动，非用户操作
  const atBottom = isAtBottom()
  isAutoFollow.value = atBottom
  showJumpBtn.value = !atBottom && messages.value.length > 0
  // 向上滚动到接近顶部时，懒加载更早的历史消息
  const el = msgListRef.value
  if (el && el.scrollTop < 300 && hasMore.value && !loadingOlder.value && !isAutoFollow.value) {
    loadOlder()
  }
}

const scrollToBottom = async () => {
  if (!isAutoFollow.value) {
    // 用户上翻中：显示「回到最新」，不打断阅读
    showJumpBtn.value = true
    return
  }
  await nextTick()
  if (msgListRef.value) {
    suppressScrollUntil()
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
  }
  showJumpBtn.value = false
}

const scrollToBottomForce = async () => {
  isAutoFollow.value = true
  await nextTick()
  if (msgListRef.value) {
    suppressScrollUntil()
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
  }
  showJumpBtn.value = false
}

const quickPrompts = [
  '我当前借了哪些书？',
  '查找哲学类图书',
  '本月借阅最多的书是什么？',
  '我有未缴罚款吗？',
]

// 对话锚点导航
// 一轮提问 = 一个锚点：序号 + 完整问题（卡片内按行截断展示）+ 时间
const anchorTime = (t) => (t ? String(t).slice(5, 16).replace('T', ' ') : '')
const anchors = computed(() => {
  let seq = 0
  return messages.value
    .filter((m) => m.role === 'user')
    .map((m) => ({
      key: String(m.id),
      seq: ++seq,
      text: String(m.content || '').replace(/\s+/g, ' ').trim(),
      time: anchorTime(m.createTime),
    }))
})

// 跳转到历史轮次后主动脱离自动跟随，否则流式增量会把用户拽回底部
const onJumpAnchor = () => {
  isAutoFollow.value = false
  showJumpBtn.value = true
}

const loadConversations = async () => {
  try {
    // A5：列表加载带退避重试（网络抖动/服务瞬时不可用时自动再试，最多 2 次）
    const data = await withRetry(() => aiApi.conversationList())
    conversations.value = data.list || []
    if (conversations.value.length) {
      await switchConv(conversations.value[0].id)
    }
  } catch (e) {
    // 加载失败静默
  }
}

const switchConv = async (id) => {
  // 多会话并行：切会话不再被流守卫拦截——后台流的增量写入各自会话的 convStates，
  // 与当前视图无关（对齐 chat-demo 任意切换行为）
  if (currentConvId.value === id) return
  currentConvId.value = id
  isAutoFollow.value = true
  showJumpBtn.value = false
  // M7：加载该会话的权限档位（切会话即切档位视图；接口失败按默认逐笔确认显示）
  const convMeta = conversations.value.find((c) => c.id === id)
  permMode.value = convMeta?.permissionMode || 'confirm'
  // 内存态即时恢复：该会话此前打开过（含后台仍在流式的），直接用缓存渲染，不等接口
  if (stateOf(id).messages.length || stateOf(id).totalMessages > 0) {
    await restorePendingCards(id)
    await scrollToBottomForce()
    return
  }
  try {
    // A5：消息加载同样走退避重试；全部失败按空会话展示并提示类目化原因
    const data = await withRetry(() => aiApi.messageList(id))
    // 后端返回最新一页（id DESC），倒转为正序后再渲染
    const page = (data.list || []).map(enrichMsg).reverse()
    const st = stateOf(id)
    st.messages = page
    st.loadedCount = page.length
    st.totalMessages = data.total || page.length
    st.oldestLoadedId = page.length ? page[0].id : null
    st.hasMore = st.loadedCount < st.totalMessages
    // A2：为可见 user 消息拉取分支导航（有兄弟版本才显示 « 2/3 »）；逐条异步不阻塞渲染
    page.filter((mm) => mm.role === 'user').forEach((mm) => loadBranchInfo(mm))
  } catch (e) {
    const st = stateOf(id)
    st.messages = []
    st.hasMore = false
    const { kind, message } = classifyAiError(e)
    // 404（会话已被其他端删除）静默清空即可；其余类目给可操作的提示
    if (kind !== AiErrorKind.NOT_FOUND) {
      ElMessage.warning(describeWithAction({ kind, message }))
    }
  }
  // M10（对齐 ZCode pendingInteractions）：恢复该会话仍待确认的授权卡。
  // 令牌在服务端有 TTL，恢复的卡带剩余有效期；过期则不再展示
  await restorePendingCards(id)
  await scrollToBottomForce()
}

/** M10：从服务端拉取待确认授权请求，重建为可操作的 pending 卡（挂到最后一条助手消息上） */
const restorePendingCards = async (convId) => {
  try {
    const list = await aiApi.pendingInteractions(convId)
    if (!Array.isArray(list) || !list.length) return
    let target = null
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'assistant') { target = messages.value[i]; break }
    }
    if (!target) return
    // M10：待确认卡可能有多张，全部恢复（confirmCards 数组）
    if (!target.confirmCards) target.confirmCards = []
    for (const p of list) {
      // 已出终态（取消/过期/已确认）的同工具卡说明决策快照已落库，
      // 服务端令牌可能只是尚未过期——跳过恢复，避免已取消的卡刷新后变回可点击
      const settled = (target.confirmCards || []).some(
        (c) => c.tool === p.tool && c.state !== 'pending'
      )
      if (settled) continue
      const card = {
        token: p.token,
        tool: p.tool,
        title: p.title || '写操作授权',
        rows: Array.isArray(p.rows) ? p.rows : [],
        ttlSeconds: p.ttlSeconds || 60,
        conversationId: convId,
        sessionAllow: true,
        alwaysAllow: false,
        state: 'pending',
        result: null,
        error: '',
      }
      target.confirmCards.push(card)
      startConfirmCountdown(card)
    }
  } catch {
    // 恢复失败静默：令牌本身有 TTL，过期自然失效
  }
}

// 向上懒加载更早的历史消息：prepend 后按 DOM 高度差保持视口位置，避免跳动
const loadOlder = async () => {
  if (loadingOlder.value || !hasMore.value || !currentConvId.value || !oldestLoadedId.value) return
  loadingOlder.value = true
  const el = msgListRef.value
  const prevHeight = el ? el.scrollHeight : 0
  const prevTop = el ? el.scrollTop : 0
  try {
    const data = await aiApi.messageList(currentConvId.value, { beforeId: oldestLoadedId.value })
    const older = (data.list || []).map(enrichMsg).reverse()
    const st = stateOf(currentConvId.value)
    if (!older.length) {
      st.hasMore = false
      return
    }
    st.messages = [...older, ...st.messages]
    st.loadedCount += older.length
    st.oldestLoadedId = older[0].id
    st.hasMore = st.loadedCount < (data.total || st.totalMessages)
  } catch (e) {
    // 加载失败静默
  } finally {
    await nextTick()
    if (el) {
      // 高度差法：内容前插后，把滚动条下移新增高度，使视口锚定的内容不跳动
      suppressScrollUntil()
      el.scrollTop = el.scrollHeight - prevHeight + prevTop
    }
    loadingOlder.value = false
  }
}

const newConv = async () => {
  // 多会话并行：新建会话不再被流守卫拦截（当前会话的流在后台继续）
  try {
    const conv = await aiApi.createConversation()
    conversations.value.unshift(conv)
    // 空状态预建：新会话无历史，直接展示空列表（切走再切回不重复拉取）
    stateOf(conv.id)
    currentConvId.value = conv.id
    isAutoFollow.value = true
    showJumpBtn.value = false
  } catch (e) {
    ElMessage.error('创建会话失败')
  }
}

const deleteConv = async (id) => {
  try {
    await ElMessageBox.confirm('确认删除该会话？删除后不可恢复。', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    // 该会话仍有流在跑：先中断（删除会话后流已无意义，避免后台继续写已删会话的缓存）
    const ctrl = streamingMap.get(id)
    if (ctrl) {
      ctrl.abort()
      streamingMap.delete(id)
    }
    streamCtxMap.delete(id)
    thinkingMap.delete(id)
    convStates.delete(id)
    // 移除该会话的排队消息
    for (let i = pendingQueue.length - 1; i >= 0; i--) {
      if (pendingQueue[i].convId === id) pendingQueue.splice(i, 1)
    }
    await aiApi.deleteConversation(id)
    const idx = conversations.value.findIndex((c) => c.id === id)
    if (idx >= 0) conversations.value.splice(idx, 1)
    if (currentConvId.value === id) {
      currentConvId.value = null
      if (conversations.value.length) {
        await switchConv(conversations.value[0].id)
      }
    }
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const sendPrompt = (prompt) => {
  input.value = prompt
  send()
}

// 登录态失效统一处理（与 api/request.js 的 handleUnauthorized 一致，SSE fetch 路径专用）：
// 清 token → 提示 → 跳登录页（携带 redirect 便于登录后回跳），防重复跳转
let redirectingToLogin = false
const handleUnauthorized = (message) => {
  localStorage.removeItem('library_token')
  if (redirectingToLogin || window.location.pathname === '/login') return
  redirectingToLogin = true
  ElMessage.error(message || '登录已过期，请重新登录')
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  setTimeout(() => {
    window.location.href = `/login?redirect=${redirect}`
  }, 500)
}

/* —— 写操作授权卡 —— */

// 确认执行：回传令牌与会话 ID，参数由服务端保存，客户端无法篡改。
// 卡片渲染在当前视图，但其 conversationId 才是续跑归属（多会话并行下视图 ≠ 流归属）
const onConfirmWrite = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  // 记录卡片所属消息（聚合续跑判断用）
  const ownerMsg = messages.value.find((m) => (m.confirmCards || []).includes(card))
  try {
    const res = await aiApi.confirmWrite(card.token, card.tool, card.conversationId, card.alwaysAllow === true)
    card.state = 'confirmed'
    card.result = res
    reportCardDecision(card, 'confirmed', card.conversationId)
    // M10：结果直接内联在授权卡上（card.result），不再追加回执文字气泡
    // M11：全部卡处理完才发起一次续跑
    maybeContinueAfterAllResolved(ownerMsg, card.tool, card.conversationId)
  } catch (e) {
    // 失败分类：令牌类失效（过期/已被处理/不匹配）→ expired；业务错误（如库存不足）→ failed。
    // 令牌一次性，失败后原卡无法重试，两种状态都只说明结果，不一律谎称过期
    const message = e?.message || '授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
    reportCardDecision(card, tokenInvalid ? 'expired' : 'failed', card.conversationId)
    // M11：失效/失败也算已处理，同样走聚合判定
    maybeContinueAfterAllResolved(ownerMsg, card.tool, card.conversationId)
  }
}

// 用户取消：卡片原地转为静默状态，不跳变
const onCancelWrite = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
  reportCardDecision(card, 'cancelled', card.conversationId)
  // M11：取消也是「已处理」，全部卡处理完触发聚合续跑
  const ownerMsg = messages.value.find((m) => (m.confirmCards || []).includes(card))
  maybeContinueAfterAllResolved(ownerMsg, card.tool || '', card.conversationId)
}

/* —— M7 批量授权卡 —— */

const batchEyebrow = (card) => {
  const state = card.state
  if (state === 'pending') return `批量授权 · ${card.count} 项操作待确认`
  if (state === 'submitting') return '正在批量办理'
  if (state === 'confirmed') return '批量办理完成'
  if (state === 'cancelled') return '已取消'
  return '批量授权失效'
}

// M9 历史授权卡（只读）的眉标
const historyCardEyebrow = (hc) => {
  const prefix = hc.batch ? '批量授权' : '授权'
  const stateText = {
    confirmed: '已确认',
    cancelled: '已取消',
    expired: '已过期',
    failed: '办理失败',
  }[hc.state] || hc.state
  return `${prefix} · ${stateText}`
}

// 全部确认：只回传批次令牌，服务端按保存的批次顺序逐项执行
const onConfirmBatch = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  try {
    const res = await aiApi.confirmBatchWrite(card.token, card.conversationId)
    card.state = 'confirmed'
    card.result = res
    reportCardDecision(card, 'confirmed', card.conversationId, true)
    // M10：批量结果内联在卡片上（confirm-batch-result 区块已有渲染），不再追加文字气泡
    // M8 确认续跑：批量办理完成后自动发起下一轮，模型汇总/继续剩余步骤
    continueAfterConfirm(`批量办理 ${res?.successCount ?? 0} 项成功`, { convId: card.conversationId })
  } catch (e) {
    const message = e?.message || '批量授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
    reportCardDecision(card, tokenInvalid ? 'expired' : 'failed', card.conversationId, true)
  }
}

// 取消本批：后端批次到期自动失效，本地置灰即可
const onCancelBatch = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
  reportCardDecision(card, 'cancelled', card.conversationId, true)
  // 批量卡取消也必须回执给模型，否则模型误认为操作悬而未决而重发授权卡
  continueAfterConfirm(card.title || '本批写操作', { rejected: true, convId: card.conversationId })
}

// 授权过期：卡片灰态保留，说明发生过什么而非凭空消失
const onConfirmExpired = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'expired'
  card.error = '授权已过期，请重新发起该操作'
  // batch 卡无 tool 字段（批次），以 tool 空串上报；batch 标记由卡片自身携带
  reportCardDecision(card, 'expired', card.conversationId, !!card.batchFlag)
}

// 授权卡倒计时：本地每秒递减 remain，归零触发 onConfirmExpired（服务端 TTL 的前端镜像）
const startConfirmCountdown = (card) => {
  stopConfirmCountdown(card)
  card.remain = card.ttlSeconds
  card._timer = setInterval(() => {
    card.remain -= 1
    if (card.remain <= 0) onConfirmExpired(card)
  }, 1000)
}
const stopConfirmCountdown = (card) => {
  if (card?._timer) {
    clearInterval(card._timer)
    card._timer = null
  }
}

/**
 * M9 上报授权卡决策态（展示层记录，失败不影响主流程）。
 * convId 显式传入（多会话并行下卡片可能属于后台会话），未传时回退当前会话。
 */
const reportCardDecision = (card, decision, convId, batch = false) => {
  const target = convId ?? currentConvId.value
  if (!card || !target) return
  aiApi.recordCardDecision({
    conversationId: target,
    decision,
    token: card.token || '',
    tool: card.tool || '',
    title: card.title || '',
    rows: Array.isArray(card.rows) ? card.rows : [],
    batch,
  }).catch(() => { /* 展示层记录失败不影响主流程 */ })
}

/**
 * M10：把确认执行的写操作结果应用到对应授权卡（对齐 ZCode 的行内状态机）。
 * tool_result SSE 事件路径：优先按 token 精确匹配（同轮可能有多张同名工具的卡）；
 * 找不到卡片（SSE 通道空闲时的确认）时静默忽略——确认接口返回值已在本地更新过卡片。
 */
const applyToolResult = (info) => {
  if (!info?.tool) return
  const candidates = []
  // 多会话并行：tool_result 推送来自确认接口的会话级通道，但卡片可能在任一会话的缓存里
  // （后台会话流已结束时推送落空，确认接口返回值已在本地更新过卡片，此处兜底搜索）
  for (const [, st] of convStates) {
    for (const m of st.messages) {
      for (const c of m.confirmCards || []) {
        if (c.tool === info.tool) candidates.push(c)
      }
      if (m.batchConfirmCard && (info.tool === 'adminCounterProcessBatch' || m.batchConfirmCard.tool === info.tool)) {
        candidates.push(m.batchConfirmCard)
      }
    }
  }
  let card = info.token
    ? candidates.find((c) => c.token === info.token)
    : candidates.reverse().find((c) => c.state === 'submitting' || c.state === 'pending')
  if (!card && info.token) {
    // token 未命中（页面刷新后恢复的卡）退化为按状态匹配
    card = candidates.reverse().find((c) => c.state === 'submitting' || c.state === 'pending')
  }
  if (!card) return
  stopConfirmCountdown(card)
  card.state = info.success === false ? 'failed' : 'confirmed'
  card.result = info
  if (info.success === false) card.error = info.message || '执行失败'
}

/**
 * 停止生成（按会话）：中断当前会话的 SSE 请求并清掉其排队消息。
 * stop 只作用于当前会话——其他会话的后台流不受影响。
 */
const stopGenerate = () => {
  const convId = currentConvId.value
  if (convId === null) return
  const ctx = getStreamCtx(convId)
  if (ctx?.abortController) {
    ctx.abortController.abort()
  }
  // 同会话的排队消息一并清除（用户明确不想继续这轮对话链）
  for (let i = pendingQueue.length - 1; i >= 0; i--) {
    if (pendingQueue[i].convId === convId) pendingQueue.splice(i, 1)
  }
  // streaming/thinking 复位交给 sendInternal 的 finally 统一收口
}

/**
 * M11（对齐 ZCode 输入区阻塞语义）：存在待确认授权卡时禁止发起新对话。
 * 权限请求阻塞当前任务——全部卡片处理完（确认/取消/过期）才能继续说话，
 * 避免「用户中途插话 → 卡片作废 → 模型重发卡」的堆积循环。
 */
const hasPendingConfirm = computed(() =>
  messages.value.some((m) =>
    (m.confirmCards || []).some((c) => c.state === 'pending') ||
    m.batchConfirmCard?.state === 'pending'
  )
)

const send = async () => {
  if (hasPendingConfirm.value) {
    ElMessage.warning('请先处理上方待确认的授权请求（确认或取消）后再继续对话')
    return
  }
  if (!input.value.trim()) return
  const content = input.value.trim()
  // 排队模型（对齐 chat-demo）：当前会话流未结束时发送不拒绝，先入队显示「排队中」气泡，
  // 本轮流 done/error/停止后由 flushQueue 自动发出；其他会话在流则直接并行发起
  const convId = currentConvId.value
  if (convId !== null && streamingMap.has(convId)) {
    pendingQueue.push({ convId, content })
    input.value = ''
    await nextTick()
    scrollToBottom()
    return
  }
  input.value = ''
  await sendInternal(content, { continuation: false })
}

/** 建议追问 chips 点击：把追问文本直接作为新消息发送（沿用排队机制，流未结束时入队） */
const router = useRouter()

/** GenUI 图书卡点击 → 进入图书详情页 */
const openBookDetail = (b) => {
  if (b?.id != null) router.push(`/book/detail/${b.id}`)
}

/** GenUI 图书卡「借阅」→ 以读者口吻把借阅请求发给 AI，走 applyBorrow 授权卡确认流程 */
const borrowFromCard = (b) => {
  if (b?.title) sendFollowUp(`我想借阅《${b.title}》`)
}

const sendFollowUp = async (text) => {
  const content = (text || '').trim()
  if (!content) return
  if (hasPendingConfirm.value) {
    ElMessage.warning('请先处理上方待确认的授权请求（确认或取消）后再继续对话')
    return
  }
  const convId = currentConvId.value
  if (convId !== null && streamingMap.has(convId)) {
    pendingQueue.push({ convId, content })
    await nextTick()
    scrollToBottom()
    return
  }
  await sendInternal(content, { continuation: false })
}

/** 排队出口：当前会话流结束后按 FIFO 发出其排队消息（一次一条，发出后若流又占线则继续排队） */
const flushQueue = async (convId) => {
  const idx = pendingQueue.findIndex((q) => q.convId === convId)
  if (idx < 0) return
  const next = pendingQueue.splice(idx, 1)[0]
  await sendInternal(next.content, { continuation: false, convId })
}

/**
 * M8 确认续跑：授权确认成功后自动发起的下一轮对话。
 * 引导语以系统续跑身份发送（不显示用户气泡），模型从系统回执读到结果继续任务链。
 * M11 聚合续跑：本轮全部授权卡处理完毕才调用（由 maybeContinueAfterAllResolved 判定）。
 * 续跑属于确认所在会话：sendInternal 已支持显式 convId，与当前视图解耦
 */
const continueAfterConfirm = async (toolSummary, { rejected = false, convId } = {}) => {
  // rejected：本轮授权被用户取消。必须把「已拒绝」明确回执给模型——
  // 否则模型读到「尚无成功回执的剩余项」会反复重发授权卡
  const guide = rejected
    ? `本轮的授权请求（${toolSummary}）已被用户取消，所有操作均未执行。用户已明确拒绝本次办理，禁止再发起任何授权卡或写操作工具调用；请简短回应已按用户要求取消，并可询问是否需要其他帮助。`
    : `授权处理已完成（${toolSummary}）。请逐项核对历史回执：已成功的项不要重复办理；已被用户取消的项是用户明确拒绝，禁止再次发起授权；仅当剩余项是因授权过期未执行时，才可为它们重新发起授权；若全部完成，请逐项列出单号汇总。`
  await sendInternal(guide, { continuation: true, convId })
}

/**
 * M11 聚合续跑：检查所属消息的授权卡是否已全部处理（非 pending），
 * 全部处理完才发起一次续跑，汇总语带各卡结果（成功/取消）。
 * convId：卡片所属会话（多会话并行下续跑归属卡片的会话，而非当前视图）
 */
const maybeContinueAfterAllResolved = (msg, toolSummary, convId) => {
  const cards = (msg?.confirmCards || []).filter((c) => c.tool)
  if (!cards.length || cards.some((c) => c.state === 'pending')) return
  const ok = cards.filter((c) => c.state === 'confirmed').length
  const skipped = cards.length - ok
  // 全部被取消：走拒绝回执，防止模型误以为操作未完成而重发授权卡
  if (ok === 0) {
    continueAfterConfirm(toolSummary, { rejected: true, convId })
    return
  }
  continueAfterConfirm(`${toolSummary}；本轮授权共 ${cards.length} 项：成功 ${ok} 项，跳过 ${skipped} 项`, { convId })
}

const sendInternal = async (content, { continuation, convId: explicitConvId, branchGroupKey }) => {
  // 多会话并行：目标会话由显式 convId 决定（排队 flush / 授权续跑各自的会话），
  // 未显式给出时用当前会话；目标会话与当前视图解耦，切换不影响已发起的流
  let convId = explicitConvId ?? currentConvId.value

  // 无会话时先创建（欢迎屏直接发送的场景）
  if (convId === null || convId === undefined) {
    try {
      const conv = await aiApi.createConversation()
      conversations.value.unshift(conv)
      stateOf(conv.id)
      convId = conv.id
      // 欢迎屏发送后停留在新建会话（与旧行为一致）
      currentConvId.value = conv.id
      isAutoFollow.value = true
    } catch (e) {
      ElMessage.error('创建会话失败')
      return
    }
  }
  const st = stateOf(convId)
  // 建议追问「仅最新回答」：发新消息即清除全部旧 chips
  for (const m of st.messages) {
    if (m.followUps) m.followUps = null
  }
  const conv = conversations.value.find((c) => c.id === convId)
  const isFirstMsg = !conv || conv.title === '新对话' || !conv.title

  // 推送用户消息（续跑轮引导语不显示用户气泡，避免伪造用户发言）。
  // A2：本地临时消息不带 _fromHistory——分支操作仅对服务端已落库的历史消息开放
  if (!continuation) {
    st.messages.push({
      id: Date.now(),
      conversationId: convId,
      role: 'user',
      content,
      createTime: new Date().toISOString(),
    })
  }
  // 目标即当前会话：强制跟随到底部；后台会话不碰滚动状态（用户可能正在阅读当前会话）
  if (convId === currentConvId.value) {
    isAutoFollow.value = true
    showJumpBtn.value = false
  }
  // 按会话登记流状态：思考占位 + 停止按钮守卫（streamingMap.has(convId) 即在流中）
  thinkingMap.set(convId, true)

  // SSE 流式请求（每会话一路，abortController 等上下文存入 streamCtxMap）
  const abortController = new AbortController()
  // A1：本条流的自适应节流渲染器
  const streamRenderer = createStreamRenderer()
  // A6：是否收到过服务端 done 事件（流提前断开时用于判定 interrupted）
  let doneReceived = false
  // 本条流自己的 turn 状态（检索联动/写操作标记），替代旧全局变量
  let pendingAiSearch = null
  let turnHasWrite = false
  const streamCtx = {
    abortController,
    streamRenderer,
  }
  streamCtxMap.set(convId, streamCtx)
  // 登记在流状态：停止按钮形态（streamingMap.has(convId)）与排队判定都以此为准
  streamingMap.set(convId, abortController)

  // 立即创建助手消息气泡（pending=true 显示「正在思考…」跳动），
  // 不等首个 SSE 事件——避免首事件前的空窗期没有任何反馈
  const assistantMsg = reactive({
    id: Date.now() + 1,
    conversationId: convId,
    role: 'assistant',
    content: '',
    html: '',
    toolEvents: [],
    // 深度思考：reasoningSegments 按「思考轮次」分段（工具调用会产生多段）；
    // reasoningStreaming 控制头部「思考中/已思考」与脉冲；reasoningOpen 控制折叠
    reasoningSegments: [],
    reasoningOpen: false,
    reasoningStreaming: false,
    reasoningSeconds: null,
    _reasonStartAt: 0,
    _reasonTimer: null,
    _reasonSegOpen: false,
    // 等待指示：true 期间显示「正在思考…」跳动；首个可见产出（思考/工具/正文）到达后置 false
    pending: true,
    // 知识库来源（SSE rag 事件），ragOpenIndex 为当前展开的片段序号，-1 表示全部收起
    ragSources: [],
    ragOpenIndex: -1,
    // 降级标记（SSE degrade 事件）：工具循环超限时的优雅收尾提示
    degraded: false,
    degradeMessage: '',
    // 写操作授权卡（SSE confirm 事件；M10 数组——同轮多个写操作各一张卡）
    confirmCards: [],
    // M7 批量授权卡（SSE batch_confirm 事件，batch 档位）
    batchConfirmCard: null,
    // M4：Agent 决策时间线（thought 旁白节点），与工具卡片交替构成决策链视图
    timeline: [],
    // M4 折叠汇总：运行态恒展开显示实时进度；done/error 后置 false → 折叠为计数汇总行
    _timelineRunning: true,
    _timelineOpen: false,
    createTime: new Date().toISOString(),
  })
  st.messages.push(assistantMsg)
  if (convId === currentConvId.value) {
    scrollToBottom()
  }

  // 思考耗时收尾：最后一段时间写入 reasoningSeconds 后停止计时（耗时保留展示）。
  // 定义在 try 之外：catch/finally 兜底路径也要调用（否则流中断时计时器泄漏）
  const stopReasonTimer = (msg) => {
    if (!msg?._reasonTimer) return
    clearInterval(msg._reasonTimer)
    msg._reasonTimer = null
    if (msg._reasonStartAt) {
      msg.reasoningSeconds = ((Date.now() - msg._reasonStartAt) / 1000).toFixed(1)
    }
  }
  try {
    const token = localStorage.getItem('library_token') || ''
    const resp = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        conversationId: convId,
        content,
        continuation,
        // A2：分支轮携带原轮分组标识（新消息落库到同组，前端分支导航聚合）
        branchGroupKey: branchGroupKey || undefined,
      }),
      signal: abortController.signal,
    })

    // 非流式响应 → 后端抛 BusinessException，返回 JSON 错误
    const ct = resp.headers.get('content-type') || ''
    if (!ct.includes('text/event-stream')) {
      let msg = 'AI 请求失败'
      let code = null
      try {
        const errBody = await resp.json()
        msg = errBody.message || msg
        code = errBody.code
      } catch {
        // 忽略
      }
      // 与 api/request.js 的 handleUnauthorized 同约定：未授权返回 body.code=401，
      // SSE 不走 axios 拦截器，这里复刻同一登出跳转（清 token → 提示 → 跳登录）
      if (code === 401) {
        handleUnauthorized(msg)
        throw new Error(msg)
      }
      throw new Error(msg)
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    const ensureAssistantMsg = () => {
      // 气泡已在发送时创建（pending=true），这里只是返回引用；
      // 首个实际事件到达时关闭「正在思考…」占位——按钮停止/发送形态由
      // streamingMap 控制（首事件后流仍在传输，停止按钮必须保持）
      if (thinkingMap.get(convId)) {
        thinkingMap.set(convId, false)
      }
      return assistantMsg
    }

    /** 滚动收口：仅当本流所属会话是当前视图会话时才滚动，后台流不干扰当前阅读 */
    const scrollToBottomIfCurrent = () => {
      if (convId === currentConvId.value) scrollToBottom()
    }

    const handleEvent = (block) => {
      const lines = block.split('\n')
      let event = 'message'
      const dataParts = []
      for (const line of lines) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          let d = line.slice(5)
          if (d.startsWith(' ')) d = d.slice(1)
          dataParts.push(d)
        }
      }
      const data = dataParts.join('\n')
      if (event === 'thought') {
        // M4：Agent 决策旁白（工具调用前的过渡语），作为时间线节点插入
        const msg = ensureAssistantMsg()
        msg.pending = false
        if (data) {
          msg.timeline.push({ kind: 'thought', text: data })
          scrollToBottomIfCurrent()
        }
      } else if (event === 'thinking') {
        // 深度思考思维链增量：流式追加到当前思考段，面板保持展开
        const msg = ensureAssistantMsg()
        msg.pending = false
        if (data) {
          if (!msg._reasonSegOpen) {
            // 上一段思维链已被工具调用/正文截断 → 新开一段（多轮工具调用场景）。
            // 首段同理：首个思考增量才建段（harness 同款——空白流式尾巴不产生行）
            if (msg.reasoningSegments.length === 0 || String(msg.reasoningSegments[msg.reasoningSegments.length - 1]).trim() !== '') {
              msg.reasoningSegments.push('')
            }
            msg._reasonSegOpen = true
          }
          if (!msg.reasoningStreaming) {
            // 本段思考开始：启动耗时计时（0.1s 粒度，避免 setData 高频）
            msg.reasoningStreaming = true
            msg.reasoningOpen = true
            msg._reasonStartAt = Date.now()
            clearInterval(msg._reasonTimer)
            msg._reasonTimer = setInterval(() => {
              msg.reasoningSeconds = ((Date.now() - msg._reasonStartAt) / 1000).toFixed(1)
            }, 100)
          }
          const segs = msg.reasoningSegments
          segs[segs.length - 1] += data
          scrollToBottomIfCurrent()
        }
      } else if (event === 'rag') {
        // 知识库来源：在调用 LLM 之前推送（早于 delta），气泡内先出「参考资料」条。
        // sourceNo（A4）与正文 [资料 N] 角标一一对应，去重按编号优先。
        // 不关 pending：检索结果不是模型产出，占位文案由「正在思考…」切换为「正在结合参考资料思考…」，
        // 覆盖 rag 之后到模型首个输出之间的空窗
        const msg = ensureAssistantMsg()
        try {
          const list = JSON.parse(data)
          if (Array.isArray(list)) {
            for (const item of list) {
              const docTitle = item?.docTitle || '未命名文档'
              const snippet = item?.snippet || ''
              const sourceNo = Number(item?.sourceNo) || null
              // 多轮工具调用可能重复检索到同一片段：按编号去重（无编号退回「标题+摘要」），避免药丸堆积
              const dup = msg.ragSources.some((s) =>
                sourceNo ? s.sourceNo === sourceNo : s.docTitle === docTitle && s.snippet === snippet
              )
              if (!dup) msg.ragSources.push({ sourceNo, docTitle, snippet })
            }
          }
        } catch {
          // 忽略解析失败的 rag 事件
        }
      } else if (event === 'delta') {
        const msg = ensureAssistantMsg()
        msg.pending = false
        // 正文开始：思维链阶段结束，停止耗时计时。
        // 面板折叠交还用户手动控制（对齐 harness：数据全量、开合由读者决定）——
        // 原实现在首个正文增量即自动折叠，模型边想边说时面板刚展开就被收起
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.content += data
        // A1：delta 高频到达，渲染走自适应节流（正文长时自动放宽间隔），done/final 时 flush 补尾。
        // 流式渲染前先补齐未闭合语法（裸 ** / 裸 ` / 未闭合围栏），终态用原始全文重渲染
        streamRenderer?.schedule(() => {
          msg.html = renderMarkdownStream(msg.content, msg.ragSources)
          scrollToBottomIfCurrent()
        })
      } else if (event === 'reset') {
        // 进入工具调用轮：清空工具调用前的过渡文本（正文只保留最终回答，
        // 工具调用过程由工具卡片承载）；本段思维链结束，停止耗时。
        // 面板保持当前开合状态——工具调用后模型通常会再思考，新一轮 thinking
        // 事件会续写展开（原实现强制折叠，多轮工具场景面板反复开合跳变）
        const msg = ensureAssistantMsg()
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.content = ''
        msg.html = ''
      } else if (event === 'final') {
        // 后端完成回答后的最终文本（已剔除误输出的工具调用代码），整体替换流式拼接内容
        const msg = ensureAssistantMsg()
        msg.content = data
        // final 是权威全文：立即渲染替换（不走节流，避免与 flush 时序竞争）
        msg.html = renderMarkdown(msg.content, msg.ragSources)
        scrollToBottomIfCurrent()
      } else if (event === 'tool') {
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const info = JSON.parse(data)
          const events = msg.toolEvents
          if (info.phase === 'start') {
            // start：新增一张「调用中」卡片
            const card = {
              name: info.name || '',
              phase: 'start',
              detail: info.detail || '',
              data: null,
              expanded: false,
            }
            events.push(card)
            // M4：工具调用记入时间线，作为决策链上的一个步骤节点
            msg.timeline.push({ kind: 'tool', name: card.name, detail: card.detail, card })
          } else {
            // end / error：更新对应的 start 卡片（同名且仍在 start 状态的最后一条），
            // 而非新增卡片——否则 start 卡片会永远停在加载态
            let target = null
            for (let i = events.length - 1; i >= 0; i--) {
              if (events[i].name === (info.name || '') && events[i].phase === 'start') {
                target = events[i]
                break
              }
            }
            const endDetail = info.detail || ''
            if (target) {
              target.phase = info.phase // end → 绿色对勾，error → 红色叉，停止旋转
              if (endDetail) {
                target.detail = target.detail
                  ? `${target.detail} · ${endDetail}`
                  : endDetail
              }
              // 结构化输出：end 阶段携带数据快照，可展开查看
              if (info.phase === 'end' && info.data) {
                target.data = info.data
                // 生成式 UI 内联判定：快照带可视化 type 时提升为一级卡片默认展开
                markInlineGen(target)
              }
            } else {
              const fbCard = {
                name: info.name || '',
                phase: info.phase,
                detail: endDetail,
                data: info.phase === 'end' ? (info.data || null) : null,
                expanded: false,
              }
              if (fbCard.data) markInlineGen(fbCard)
              events.push(fbCard)
            }
            // AI 检索联动：工具返回的图书快照先暂存候补，done 时统一判定（本条注释与悬浮球面板一致）。
            // 不限定工具名：只要快照含图书条目（items 数组且元素带 title）即成为候补；
            // 本轮若带写操作（如借书前的 queryBooks），候补不广播
            if (info.phase === 'end' && info.data) {
              try {
                const snap = JSON.parse(info.data)
                const items = snap.items
                if (Array.isArray(items) && items.length && items[0]?.title) {
                  pendingAiSearch = { query: lastUserText.value || '', items, total: snap.total }
                }
              } catch { /* 快照异常不影响对话 */ }
            }
          }
        } catch {
          // 忽略解析失败的 tool 事件
        }
        scrollToBottomIfCurrent()
      } else if (event === 'done') {
        doneReceived = true
        thinkingMap.set(convId, false)
        // 检索联动广播已收窄到悬浮球面板（仅馆藏图书页时写入 aiSearchStore）；
        // AI 助手页自身有完整检索展示，queryBooks 结果只留在对话内，不跨页投递
        pendingAiSearch = null
        turnHasWrite = false
        // A1：流正常结束，把节流中未渲染的尾部内容立即上屏
        streamRenderer?.flush()
        if (assistantMsg) {
          assistantMsg.pending = false
          stopReasonTimer(assistantMsg)
          assistantMsg._reasonSegOpen = false
          assistantMsg.reasoningStreaming = false
          // M4 折叠汇总：流正常结束，时间线切换为折叠态（汇总行 + 可手动展开）
          assistantMsg._timelineRunning = false
        }
        // 流结束滚动定位：本轮有内联生成式卡片时滚到本轮第一张卡片，否则维持滚底
        if (convId === currentConvId.value) {
          scrollToFirstGenCard(assistantMsg).then((hit) => {
            if (!hit) scrollToBottom()
          })
        }
        if (isFirstMsg && conv) {
          conv.title = content.length > 20 ? content.substring(0, 20) + '…' : content
        }
        // A2：本轮流结束（消息已落库），为本轮 user 消息拉取分支导航——
        // 分支轮（编辑重发/重新生成）落库后同组出现新兄弟，导航立即更新。
        // done 事件回传本轮 user 消息的真实落库 id：本地推送的是 Date.now()
        // 临时 id，不回传真实 id 的话分支查询/编辑重发必 404
        try {
          const doneInfo = JSON.parse(data)
          if (doneInfo?.userMessageId) {
            const localUserMsg = !continuation ? st.messages[st.messages.length - 2] : null
            if (localUserMsg && localUserMsg.role === 'user') {
              localUserMsg.id = doneInfo.userMessageId
              localUserMsg._fromHistory = true
              loadBranchInfo(localUserMsg)
            }
          }
        } catch { /* 兼容旧格式 [DONE]：无分支导航，不影响对话 */ }
      } else if (event === 'followups') {
        // 建议追问（方案 A）：后端 done 后后台生成，经同一 SSE 推送（JSON 字符串数组）。
        // 挂到本轮助手消息上渲染 chips；仅最新回答显示——若用户已发出新消息
        // （该消息不再是末条），丢弃本事件，避免旧 chips 复活
        if (assistantMsg && st.messages[st.messages.length - 1] === assistantMsg) {
          try {
            const list = JSON.parse(data)
            if (Array.isArray(list) && list.length) assistantMsg.followUps = list
          } catch { /* 解析失败忽略 */ }
        }
      } else if (event === 'confirm') {
        // 写操作授权请求：模型发起写操作被门禁拦截，改为向用户请求授权。
        // 卡片渲染在工具卡片之后，正文不受影响（后端拦截时不会发 reset 事件）。
        // 本轮出现写操作：检索候补不再广播（借书前的 queryBooks 只是流程中间步骤）
        turnHasWrite = true
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const info = JSON.parse(data)
          // M10：同轮可能有多个写操作，卡进数组互不覆盖
          if (!msg.confirmCards) msg.confirmCards = []
          msg.confirmCards.push({
            token: info.token,
            tool: info.tool,
            title: info.title || '写操作授权',
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 60,
            // 确认请求回传，供服务端校验令牌与会话归属一致
            conversationId: convId,
            // M8「本会话始终允许」勾选状态（后端 sessionAllow 开关存在时展示）
            sessionAllow: info.sessionAllow === true,
            alwaysAllow: false,
            // pending: 等待确认 / confirmed: 已确认 / cancelled: 用户取消 / expired: 已过期 / failed: 办理失败
            state: 'pending',
            result: null,
            error: '',
          })
          // M4：授权请求记入时间线，与旁白、工具卡片构成完整决策链
          msg.timeline.push({ kind: 'confirm', tool: info.tool || '' })
          msg.content = msg.content || '请确认上方授权请求后继续。'
          msg.html = renderMarkdown(msg.content, msg.ragSources)
          startConfirmCountdown(msg.confirmCards[msg.confirmCards.length - 1])
          scrollToBottomIfCurrent()
        } catch {
          // 解析失败时不影响对话
        }
      } else if (event === 'batch_confirm') {
        // M7 批量授权卡（batch 档位）：本轮多个写操作合并一张卡，一次确认整批。
        // 后端在 done 前推送；此时助手消息已基本成形，卡片追加在其后
        turnHasWrite = true
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const info = JSON.parse(data)
          msg.batchConfirmCard = {
            token: info.token,
            title: info.title || '批量授权',
            count: info.count || 0,
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 120,
            conversationId: convId,
            batchFlag: true,
            // pending: 等待确认 / submitting / confirmed / cancelled / expired / failed
            state: 'pending',
            result: null,
            error: '',
          }
          msg.timeline.push({ kind: 'confirm', tool: 'batch:' + (info.count || 0) })
          msg.content = msg.content || `本轮共 ${info.count || 0} 项写操作待授权，请在下方批量卡上一并确认。`
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.batchConfirmCard)
          scrollToBottomIfCurrent()
        } catch {
          // 解析失败时不影响对话
        }
      } else if (event === 'tool_result') {
        // M10：确认执行的写操作结果（对齐 ZCode：结果内联在授权卡上原地更新，
        // 不再出现回执文字气泡）。推送来自确认接口的会话级通道
        try {
          const info = JSON.parse(data)
          applyToolResult(info)
        } catch {
          // 忽略解析失败
        }
      } else if (event === 'degrade') {
        // 工具循环超限降级：非报错样式，提示已完成部分 + 建议继续追问
        const msg = ensureAssistantMsg()
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        try {
          const info = JSON.parse(data)
          msg.degraded = true
          msg.degradeMessage = info.message || '任务较复杂，已为你简化处理。'
        } catch {
          msg.degraded = true
          msg.degradeMessage = '任务较复杂，已为你简化处理。'
        }
        // 已有正文（模型在超限前输出的一部分）保留在前，降级说明追加其后
        msg.content = msg.content
          ? `${msg.content}\n\n${msg.degradeMessage}`
          : msg.degradeMessage
        msg.html = renderMarkdown(msg.content, msg.ragSources)
        scrollToBottomIfCurrent()
      } else if (event === 'notice') {
        // 渠道故障转移等系统级提示：轻提示告知用户（如「主渠道繁忙，已切换到备用渠道」），不打断回答流
        ElMessage({ type: 'info', message: data, duration: 4000 })
      } else if (event === 'error') {
        thinkingMap.set(convId, false)
        const msg = ensureAssistantMsg()
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        // 已有正文时保留原内容，错误说明追加其后（避免中途出错抹掉已流出的回答）
        const errTip = `⚠️ ${data || 'AI 请求出错'}`
        msg.content = msg.content ? `${msg.content}\n\n${errTip}` : errTip
        msg.html = renderMarkdown(msg.content, msg.ragSources)
        scrollToBottomIfCurrent()
      }
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (block.trim()) handleEvent(block)
      }
    }
    if (buffer.trim()) handleEvent(buffer)
    // A6：流提前结束（read done 但没收到 done 事件）= 服务端中断/容器超时。
    // 与正常结束同样 flush 收尾，并给用户中断提示——区别于红色报错，保留已生成正文
    if (!doneReceived && assistantMsg) {
      streamRenderer?.flush()
      const tip = '⚠️ 回复传输中断，以上内容可能不完整'
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${tip}` : tip
      assistantMsg.html = renderMarkdown(assistantMsg.content, assistantMsg.ragSources)
    }
  } catch (e) {
    thinkingMap.set(convId, false)
    // A1：中断/异常时立即渲染已累积内容（丢弃未执行的节流任务由 dispose 完成）
    streamRenderer?.flush()
    if (e.name === 'AbortError') {
      // 用户主动停止：保留已生成内容
      return
    }
    // A5：错误分类 + 文案分离——气泡内追加类目化提示，网络类不弹全局 toast（气泡已可见）
    const { kind, message } = classifyAiError(e)
    const friendly = describeWithAction({ kind, message })
    if (kind !== AiErrorKind.NETWORK) {
      ElMessage.error(friendly)
    }
    if (assistantMsg) {
      // 已有正文时保留原内容，错误说明追加其后
      const errTip = `⚠️ ${friendly}`
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${errTip}` : errTip
      assistantMsg.html = renderMarkdown(assistantMsg.content, assistantMsg.ragSources)
    }
  } finally {
    streamRenderer?.dispose()
    // 多会话并行收口：注销本会话的流登记——任何退出路径（done/error/abort/finally 兜底）
    // 都要清 streamingMap 与 thinkingMap，该会话按钮回到「发送」态；其他会话的流不受影响
    streamingMap.delete(convId)
    streamCtxMap.delete(convId)
    thinkingMap.set(convId, false)
    // 流结束（完成/停止/出错）后，仍在加载态的工具卡片停止旋转
    if (assistantMsg && assistantMsg.toolEvents) {
      assistantMsg.toolEvents.forEach((t) => {
        if (t.phase === 'start') t.phase = 'end'
      })
    }
    // 思维链状态兜底：中断/异常时停止计时、停止「思考中」脉冲并折叠
    if (assistantMsg) {
      assistantMsg.pending = false
      stopReasonTimer(assistantMsg)
      assistantMsg._reasonSegOpen = false
      assistantMsg.reasoningStreaming = false
      // M4 折叠汇总兜底：error/中断/停止路径也要结束运行态（done 之外的退出都走这里）
      assistantMsg._timelineRunning = false
    }
    // 排队出口：本会话还有排队消息时自动发出下一条（排队模型的闭环）
    flushQueue(convId)
  }
}

const formatTime = (t) => {
  if (!t) return ''
  return t.substring(5, 10)
}

onBeforeUnmount(() => {
  // 组件卸载（keep-alive 场景不会触发）：中断所有会话的在跑流并清理定时器
  for (const [, ctx] of streamCtxMap) {
    ctx.abortController?.abort()
    ctx.streamRenderer?.dispose()
  }
  streamCtxMap.clear()
  streamingMap.clear()
  thinkingMap.clear()
  for (const [, st] of convStates) {
    st.messages.forEach((m) => {
      for (const c of m.confirmCards || []) stopConfirmCountdown(c)
      stopConfirmCountdown(m.batchConfirmCard)
    })
  }
})

/* ---------- 与悬浮球面板的会话同步 ----------
 * 悬浮球（AiChatPanel）每次新建会话/发送消息都会广播 ai-session-activity；
 * 本页被 keep-alive 缓存、切回时不走挂载，onActivated 时据广播同步视图：
 * 同一会话 → 有新消息才静默重拉，不打扰当前浏览位置；
 * 不同会话 → 刷新会话列表后直接切到悬浮球的会话 */
let panelActiveConvId = null
const onPanelActivity = (e) => {
  const id = e.detail?.conversationId
  if (id) panelActiveConvId = id
}
window.addEventListener('ai-session-activity', onPanelActivity)
onBeforeUnmount(() => window.removeEventListener('ai-session-activity', onPanelActivity))

const syncFromPanel = async () => {
  if (!panelActiveConvId) return
  if (panelActiveConvId === currentConvId.value) {
    // 多会话并行：本会话正有流在跑时静默跳过——流增量已实时写入当前视图，重拉反而会闪断
    if (streamingMap.has(panelActiveConvId)) return
    try {
      const data = await aiApi.messageList(panelActiveConvId)
      // 悬浮球没有产生新消息时保持现状，不打断当前滚动位置
      if ((data.total || 0) === totalMessages.value) return
      const page = (data.list || []).map(enrichMsg).reverse()
      const st = stateOf(panelActiveConvId)
      st.messages = page
      st.loadedCount = page.length
      st.totalMessages = data.total || page.length
      st.oldestLoadedId = page.length ? page[0].id : null
      st.hasMore = st.loadedCount < st.totalMessages
      await scrollToBottomForce()
    } catch {
      // 静默
    }
  } else {
    try {
      const data = await aiApi.conversationList()
      conversations.value = data.list || []
    } catch {
      // 列表刷新失败也继续切换，switchConv 自带容错
    }
    await switchConv(panelActiveConvId)
  }
}

onActivated(syncFromPanel)

/* ==================== 语音输入（ASR）====================
 * WebAudio 录 16kHz 单声道 WAV → 上传后端中转云端转写（渠道配置在系统设置）。
 * 录音中再次点击结束并转写；达 120s 上限自动停止转写（服务端同限校验，取
 * DeepSeek ASR 最严标准）。文本回填输入框可编辑后再发送，不直接触发发送。
 */
const ASR_MAX_MS = 120000
const asrAvailable = ref(false)
const asrRecording = ref(false)
const asrTranscribing = ref(false)
const asrRecordingSec = ref(0)
let micRecorder = null
let asrTickTimer = null

/** 麦克风按钮显隐由后端渠道配置决定；获取失败仅隐藏按钮，不阻塞聊天 */
const checkAsrStatus = async () => {
  try {
    const data = await asrApi.status()
    asrAvailable.value = !!data.available
  } catch {
    asrAvailable.value = false
  }
}

const stopAsrTick = () => {
  clearInterval(asrTickTimer)
  asrTickTimer = null
}

/** 录音收尾：上传转写并把文本回填输入框（已有草稿时追加，不覆盖） */
const finishAsr = async (result) => {
  stopAsrTick()
  asrRecording.value = false
  micRecorder = null
  if (!result) return
  const { blob, durationMs } = result
  if (durationMs < 600) {
    ElMessage.warning('录音时间太短，请点击后正常说话')
    return
  }
  asrTranscribing.value = true
  try {
    // 不传 language：DeepSeek 自动识别语种，其 API 未定义 language 字段，
    // 多余表单字段有被上游 400 拒绝的风险；OpenAI 兼容端点同样支持自动识别
    const data = await asrApi.transcribe(new File([blob], 'speech.wav', { type: 'audio/wav' }))
    const text = (data.text || '').trim()
    if (!text) {
      ElMessage.info('未识别到语音内容，请再试一次')
      return
    }
    input.value = input.value ? `${input.value} ${text}` : text
  } catch {
    // 错误已由拦截器提示（渠道未配置/超时/超限等）
  } finally {
    asrTranscribing.value = false
  }
}

const toggleVoiceInput = async () => {
  if (asrTranscribing.value) return
  if (asrRecording.value) {
    const result = await micRecorder?.stop()
    await finishAsr(result)
    return
  }
  micRecorder = new MicRecorder({
    maxDurationMs: ASR_MAX_MS,
    onMaxDuration: (result) => {
      ElMessage.info('已达 120 秒录音上限，自动开始转写')
      finishAsr(result)
    },
  })
  try {
    await micRecorder.start()
    asrRecording.value = true
    asrRecordingSec.value = 0
    asrTickTimer = setInterval(() => { asrRecordingSec.value++ }, 1000)
  } catch (e) {
    micRecorder = null
    ElMessage.error(e?.message || '麦克风启动失败')
  }
}

onBeforeUnmount(() => {
  stopAsrTick()
  micRecorder?.cancel()
})

onMounted(() => {
  loadConversations()
  checkAsrStatus()
})
</script>

<style scoped>
/* 布局：高度钉死，仅消息区滚动 */
.ai-page {
  display: grid;
  grid-template-columns: 280px 1fr;
  grid-template-rows: 1fr;          /* 显式 1fr 行，约束子项不随内容增高 */
  height: calc(100vh - var(--header-height));
  overflow: hidden;
  /* 顶栏对齐：侧栏会话头与聊天头共用同一高度 */
  --ai-head-h: 64px;
}

/* 会话侧栏 */
.conv-side {
  background: var(--color-bg-elevated);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.conv-head {
  height: var(--ai-head-h);
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--color-divider);
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.new-btn { width: 100%; }

.conv-list {
  flex: 1;
  min-height: 0;                    /* flex 滚动关键 */
  overflow-y: auto;
  padding: var(--space-3);
}

.conv-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-soft);
  margin-bottom: var(--space-1);
}
.conv-item:hover { background: var(--color-bg-subtle); }
.conv-item.active { background: var(--color-primary-light); }

.conv-icon { margin-top: 2px; color: var(--color-text-tertiary); }
.conv-item.active .conv-icon { color: var(--color-primary); }

.conv-del {
  position: absolute;
  top: var(--space-3);
  right: var(--space-3);
  color: var(--color-text-tertiary);
  opacity: 0;
  transition: all var(--duration-fast) var(--ease-soft);
  padding: 2px;
  border-radius: var(--radius-sm);
}
.conv-item:hover .conv-del { opacity: 1; }
.conv-del:hover {
  color: var(--color-danger);
  background: rgba(168, 90, 74, 0.1);
}

.conv-meta { flex: 1; min-width: 0; padding-right: 20px; }
.conv-title {
  font-size: var(--fs-small);
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 2px;
}
.conv-item.active .conv-title { color: var(--color-primary); font-weight: 500; }
.conv-time {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 对话主区 */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
  /* 微妙纸感：顶部一抹暖光，营造空间纵深 */
  background:
    radial-gradient(120% 60% at 50% -10%, rgba(58, 90, 107, 0.05), transparent 60%),
    var(--color-bg);
}

.chat-head {
  height: var(--ai-head-h);
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elevated);
  z-index: 2;
}

.head-id .chat-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  margin-top: 2px;
  line-height: 1.2;
}

.head-tip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 消息滚动区（唯一滚动容器） */
.msg-scroll {
  flex: 1;
  min-height: 0;                    /* flex 滚动关键：允许收缩触发 overflow */
  overflow-y: auto;
  overflow-x: hidden;
  /* 锚点定位时在顶部留出呼吸位，问题不会紧贴容器边缘 */
  scroll-padding-top: var(--space-4);
}

.msg-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-6) 0;
}

.msg-bottom-gap { height: var(--space-4); }

/* 欢迎屏 */
.welcome {
  text-align: center;
  padding: var(--space-8) var(--space-5) var(--space-6);
  max-width: 560px;
  margin: 0 auto;
}

.welcome-mark {
  width: 60px;
  height: 60px;
  margin: 0 auto var(--space-5);
  border-radius: var(--radius-lg);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-serif);
  font-size: 1.9rem;
  font-weight: 600;
  box-shadow: var(--shadow-md);
}

.welcome-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h2);
  font-weight: 600;
  margin-bottom: var(--space-3);
}
.welcome-desc {
  color: var(--color-text-secondary);
  margin-bottom: var(--space-6);
}

.quick-prompts {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  text-align: left;
}
.quick-prompt {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  cursor: pointer;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  transition: all var(--duration-fast) var(--ease-soft);
}
.quick-prompt:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
  color: var(--color-primary);
  transform: translateX(2px);
}
.quick-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-primary);
  flex-shrink: 0;
  opacity: 0.5;
}

/* 日期分隔线 */
.day-divider {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin: var(--space-6) 0 var(--space-5);
}
.day-line {
  flex: 1;
  height: 1px;
  background: var(--color-divider);
}
.day-label {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
  white-space: nowrap;
  font-family: var(--font-serif);
}

/* 消息行 */
.msg-row {
  display: flex;
  margin-bottom: var(--space-5);
  animation: msg-in var(--duration) var(--ease-out);
}
@keyframes msg-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 用户：右对齐气泡 */
.msg-row.user {
  justify-content: flex-end;
}
/* A2：用户消息右列——分支导航 / 气泡 / 操作条纵向排列。
   宽度封顶在列上：列的百分比相对 .msg-inner（860px 容器）解析，
   若封顶写在气泡上，气泡的百分比会相对「内容宽度即列宽」的父列
   解析，形成自引用——短消息被截到自身宽度的 78% 提前折行 */
.user-col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  max-width: 78%;
}
/* 分支导航：« 2/3 » 版本切换（chat-demo 同款交互） */
.branch-nav {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.branch-arrow {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  transition: all var(--duration-fast) var(--ease-soft);
}
.branch-arrow:hover:not(:disabled) {
  background: var(--color-bg-subtle);
  color: var(--color-primary);
}
.branch-arrow:disabled { opacity: 0.3; cursor: default; }
.branch-pos { font-family: var(--font-mono); min-width: 28px; text-align: center; }
/* 悬停操作条：编辑 / 重新生成（默认隐藏，悬停气泡列时浮现） */
.user-actions {
  display: flex;
  gap: 10px;
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-soft);
}
.msg-row.user:hover .user-actions { opacity: 1; }
.user-action {
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: var(--fs-caption);
  cursor: pointer;
  padding: 2px 0;
  transition: color var(--duration-fast) var(--ease-soft);
}
.user-action:hover:not(:disabled) { color: var(--color-primary); }
.user-action:disabled { opacity: 0.4; cursor: default; }
/* 编辑框：就地展开在气泡下方（右对齐） */
.edit-box {
  width: min(480px, 100%);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.user-bubble {
  max-width: 100%;
  background: var(--color-primary);
  color: var(--color-text-inverse);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-lg) var(--radius-lg) var(--radius-sm) var(--radius-lg);
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: var(--shadow-sm);
}
/* 排队气泡：半透明 + 虚线边框，与已发出的消息在视觉上区分（还没发出去） */
.user-bubble.queued {
  background: transparent;
  color: var(--color-text-secondary);
  border: 1.5px dashed var(--color-border-strong);
  box-shadow: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.user-bubble.queued .queued-tag {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 助手：左对齐头像 + 内容 */
.msg-row.assistant {
  gap: var(--space-3);
  justify-content: flex-start;
}
.msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: var(--fs-small);
  background: var(--color-primary);
  color: var(--color-text-inverse);
}
.msg-body {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 46px);
}

/* 助手消息：空气化排版，不套重卡片 */
.msg-content {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text);
  padding: var(--space-1) 0;
  word-break: break-word;
}
.msg-content.thinking {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-tertiary);
}
.thinking-text {
  font-size: var(--fs-caption);
  margin-left: 2px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  animation: pulse 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
@keyframes pulse {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* 深度思考（思维链）面板 */
.reason-panel {
  margin-bottom: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  overflow: hidden;
}
.reason-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  transition: background var(--duration-fast) var(--ease-soft);
}
.reason-head:hover { background: var(--color-bg-muted); }
.reason-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-primary-light);
  color: var(--color-primary);
  flex-shrink: 0;
}
.reason-title {
  font-family: var(--font-serif);
  font-weight: 600;
  letter-spacing: 0.02em;
}
/* 思考耗时（如 12.4s）：等宽数字避免跳动 */
.reason-time {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-secondary);
  opacity: 0.85;
}
.reason-chevron {
  margin-left: auto;
  display: inline-flex;
  transition: transform var(--duration-fast) var(--ease-soft);
  color: var(--color-text-tertiary);
}
.reason-chevron.open { transform: rotate(180deg); }

/* 思考中脉冲点（与等待态 .dot 同款呼吸节奏，尺寸更小） */
.reason-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: reason-pulse 1.2s infinite ease-in-out both;
}
@keyframes reason-pulse {
  0%, 80%, 100% { transform: scale(0.7); opacity: 0.45; }
  40% { transform: scale(1.15); opacity: 1; }
}

/* 思维链正文：小号次级文字。不限高——开合由用户控制（对齐 harness 数据全量原则），
   限高会把长思考藏进内滚小窗，「显示不全」的观感正源于此 */
.reason-body {
  padding: 0 12px 10px 38px;
  font-size: var(--fs-caption);
  line-height: 1.75;
  color: var(--color-text-secondary);
}
.reason-seg {
  white-space: pre-wrap;
  word-break: break-word;
}
/* 多轮思考之间的分隔（工具调用后再次思考） */
.reason-sep {
  display: flex;
  align-items: center;
  margin: 8px 0;
}
.reason-sep span {
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

/* 展开/折叠过渡：与工具数据抽屉同款动效 */
.reason-expand-enter-active,
.reason-expand-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft),
              transform var(--duration-fast) var(--ease-soft);
  transform-origin: top;
}
.reason-expand-enter-from,
.reason-expand-leave-to {
  opacity: 0;
  transform: translateY(-4px) scaleY(0.96);
}

/* —— 写操作授权卡 ——
   形态刻意区别于工具卡片（卡片 + 竖条 vs pill），语义上也区别于降级提示（陶土黄）：
   这是「用户必须做决策」的唯一入口，需要最强的视觉信号 */
.confirm-card {
  margin-bottom: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius);
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-primary);
  background: var(--color-bg-elevated);
  transition: all var(--duration) var(--ease-soft);
}
.confirm-head { margin-bottom: var(--space-1); }
.confirm-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  color: var(--color-primary);
}
.confirm-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  color: var(--color-text);
  line-height: var(--lh-snug);
}
/* 参数键值表 */
.confirm-rows {
  margin-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
}
.confirm-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-divider);
}
.confirm-row:last-child { border-bottom: none; }
.confirm-k {
  flex-shrink: 0;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.confirm-v {
  font-size: var(--fs-caption);
  color: var(--color-text);
  text-align: right;
  word-break: break-all;
}
/* 操作区 */
.confirm-actions {
  margin-top: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.confirm-btn {
  padding: 6px 16px;
  border-radius: var(--radius);
  border: 1px solid var(--color-border);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: var(--fs-small);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}
.confirm-btn:hover { border-color: var(--color-border-strong); color: var(--color-text); }
.confirm-btn.primary {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-inverse);
}
.confirm-btn.primary:hover { background: var(--color-primary-hover); }
.confirm-ttl {
  margin-left: auto;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.confirm-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}
.confirm-state.ok { color: var(--color-success); }
.confirm-state.muted { color: var(--color-text-tertiary); }
.confirm-state.err { color: var(--color-danger); }

/* 状态变体：处理完成后收敛视觉重量，竖条与边框转为中性 */
.confirm-card.confirmed,
.confirm-card.cancelled,
.confirm-card.expired {
  border-left-color: var(--color-border-strong);
}
.confirm-card.confirmed .confirm-eyebrow { color: var(--color-success); }
.confirm-card.cancelled .confirm-eyebrow { color: var(--color-text-tertiary); }
.confirm-card.expired .confirm-eyebrow { color: var(--color-danger); }

/* —— 降级提示（工具循环超限） ——
   区别于 error 的红色报错：沿用项目陶土黄警示色系（--color-warning），
   传达「已简化处理」而非「出错了」；不新增设计 token */
.degrade-tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: var(--space-2);
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(196, 144, 74, 0.1);
  border: 1px solid rgba(196, 144, 74, 0.3);
  color: var(--color-warning);
  font-size: var(--fs-caption);
  line-height: 1.7;
}
.degrade-tip span {
  flex: 1;
}

/* —— 参考资料（知识库检索来源） ——
   沿用工具卡片的视觉语言：纸色药丸 + 虚线下分隔，不新增视觉语言 */
.rag-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
}
.rag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.rag-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.rag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  border: 1px dashed var(--color-border);
  background: var(--color-bg-elevated);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}
.rag-chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.rag-chip.active {
  border-style: solid;
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  color: var(--color-primary);
}
/* 文档名可能很长：单行省略，完整名称由 title 悬停给出 */
.rag-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-snippet {
  max-width: 560px;
  margin-top: var(--space-1);
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-bg-subtle);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
.rag-snippet p {
  margin: 6px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* —— M4：Agent 决策时间线 ——
   左侧一条竖轴串起「思考旁白 → 工具调用 → 授权请求」，
   轴线用项目的主色淡化版，节点圆点实心，形成清晰的步骤感 */
.timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  padding-left: 22px;
}
/* 竖轴：贯穿整条时间线 */
.timeline::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 8px;
  bottom: 8px;
  width: 1px;
  background: var(--color-divider);
}
.timeline-node {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
/* 节点圆点：骑在竖轴上 */
.timeline-seq {
  position: absolute;
  left: -22px;
  top: 4px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border-strong);
  color: var(--color-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  font-family: var(--font-mono);
  line-height: 1;
}
.timeline-node.thought .timeline-seq { border-color: var(--color-border-strong); }
.timeline-node.tool .timeline-seq { border-color: var(--color-primary); color: var(--color-primary); }
.timeline-node.confirm .timeline-seq { border-color: var(--color-warning); color: var(--color-warning); }
/* 思考旁白：斜体 + 弱化，与工具卡片的实心 pill 形成层级差 */
.timeline-thought {
  padding: 2px 0;
  font-size: var(--fs-small);
  font-style: italic;
  line-height: var(--lh-relaxed);
  color: var(--color-text-tertiary);
  border-left: 2px solid var(--color-divider);
  padding-left: var(--space-3);
}
/* 授权请求节点 */
.timeline-confirm {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  border: 1px dashed var(--color-warning);
  color: var(--color-warning);
  background: var(--color-bg-elevated);
  font-size: var(--fs-caption);
}
/* 时间线内的工具卡片：去掉左侧缩进，宽度自适应 */
.timeline-node.tool .tool-card { width: 100%; }
.timeline-node.tool .tool-head { align-self: flex-start; }

/* 工具调用卡片 */
.tool-events {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: var(--space-2);
}
/* 多工具串联计数头部 */
.tool-chain-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-subtle);
  border: 1px dashed var(--color-border);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-bottom: 2px;
}
.chain-icon { font-size: 12px; }

/* M4 折叠汇总行：结束态的时间线入口（harness 式类别计数标题） */
.timeline-summary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  align-self: flex-start;
  border: 1px dashed var(--color-border);
  background: var(--color-bg-subtle);
  border-radius: var(--radius-pill);
  padding: 5px 12px;
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
  margin-bottom: var(--space-2);
  transition: all var(--duration-fast) var(--ease-soft);
}
.timeline-summary:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.timeline-summary .summary-icon { font-size: 13px; }
.timeline-summary .summary-time {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-tertiary);
}
.timeline-summary .summary-chevron {
  transition: transform var(--duration-fast) var(--ease-soft);
  color: var(--color-text-tertiary);
}
.timeline-summary .summary-chevron.open { transform: rotate(180deg); }

/* tool-card 是容器，不再承载 pill 样式；负责纵向堆叠 head + data */
.tool-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
}
/* pill 形头部：紧凑横排，永不变形 */
.tool-head {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 24px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  position: relative;
  transition: all var(--duration-fast) var(--ease-soft);
}
.tool-card.start .tool-head { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-primary-light); }
.tool-card.end .tool-head { border-color: var(--color-success); color: var(--color-success); }
.tool-card.error .tool-head { border-color: var(--color-danger); color: var(--color-danger); }
.tool-head .tool-icon { animation: spin 1s linear infinite; }
.tool-card.end .tool-head .tool-icon,
.tool-card.error .tool-head .tool-icon { animation: none; }
@keyframes spin { to { transform: rotate(360deg); } }
.tool-name { font-weight: 500; }
.tool-detail { color: var(--color-text-tertiary); }

/* 序号角标：调用顺序，钉在 pill 左侧 */
.tool-seq {
  position: absolute;
  left: 8px;
  top: 50%;
  transform: translateY(-50%);
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--color-bg-subtle);
  color: var(--color-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-family: var(--font-mono);
  line-height: 1;
  border: 1px solid var(--color-border);
}
.tool-card.start .tool-seq { background: var(--color-primary); color: var(--color-text-inverse); border-color: var(--color-primary); }
.tool-card.end .tool-seq { background: var(--color-success); color: var(--color-text-inverse); border-color: var(--color-success); }
.tool-card.error .tool-seq { background: var(--color-danger); color: #fff; border-color: var(--color-danger); }

/* 展开按钮 */
.tool-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 0;
  transition: all var(--duration-fast) var(--ease-soft);
}
.tool-toggle:hover,
.tool-toggle.active {
  background: var(--color-bg-subtle);
  color: var(--color-primary);
}

/* 展开面板：独立于 pill 之外的下沉抽屉，与 pill 视觉关联但边界分明 */
.tool-data {
  align-self: stretch;
  margin-top: 4px;
  margin-left: 14px;
  padding: 8px 12px 10px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  border-top: none;
  border-top-left-radius: 0;
  border-bottom-right-radius: var(--radius-md);
  overflow-x: auto;
  position: relative;
}
/* M5：生成式 UI 容器 —— 内容自带卡片，容器只做留白与背景承载 */
.gen-ui-wrap { padding: 10px 12px 12px; }

/* ===== 生成式 UI 一级内联卡片 =====
   带可视化 type 的工具快照平铺渲染在时间线之后，默认展开，无需点击工具 pill */
.gen-ui-inline {
  align-self: stretch;
  margin: 10px 0 6px;
  padding: 12px 14px 14px;
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
}
.gen-ui-inline-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.gen-ui-inline-label .el-icon { color: var(--color-primary); }
.gen-ui-time {
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--color-text-tertiary, #9c958d);
  background: var(--color-bg-elevated, #fff);
  border: 1px solid var(--color-border, #e8e3dc);
}
/* 抽屉左上角与 pill 的视觉连接线 */
.tool-data::before {
  content: '';
  position: absolute;
  left: -1px;
  top: -8px;
  width: 2px;
  height: 8px;
  background: var(--color-border);
}
.tool-data-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
  font-family: var(--font-mono);
}
.tool-data-label .el-icon { font-size: 11px; }
.tool-data pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 展开/折叠过渡动画 */
.tool-expand-enter-active,
.tool-expand-leave-active {
  transition: opacity var(--duration-fast) var(--ease-soft),
              transform var(--duration-fast) var(--ease-soft);
  transform-origin: top;
}
.tool-expand-enter-from,
.tool-expand-leave-to {
  opacity: 0;
  transform: translateY(-4px) scaleY(0.96);
}

/* Markdown 排版已抽至 styles/ai-markdown.css（utils/markdown.js 导入生效，chat 页与悬浮球共用） */

/* 「回到最新」浮动按钮 */
.jump-latest {
  position: absolute;
  left: 50%;
  bottom: 148px;                         /* 锚定在输入盒上方，不随内容滚动 */
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 4px;
  background: var(--color-bg-elevated);
  color: var(--color-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  padding: 8px 16px;
  cursor: pointer;
  font-size: var(--fs-caption);
  font-weight: 500;
  box-shadow: var(--shadow-md);
  z-index: 6;
  transition: all var(--duration-fast) var(--ease-soft);
}
.jump-latest:hover {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-color: var(--color-primary);
  transform: translateX(-50%) translateY(-2px);
}
.jump-enter-active, .jump-leave-active {
  transition: all var(--duration) var(--ease-out);
}
.jump-enter-from, .jump-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px);
}

/* 输入区（固定底部） */
.composer {
  flex-shrink: 0;
  padding: var(--space-3) var(--space-6) var(--space-4);
  background: linear-gradient(to top, var(--color-bg) 70%, transparent);
  z-index: 2;
}

/* M8 「本会话始终允许」勾选项 */
.confirm-allow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-right: auto;
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-secondary, #5c564f);
  cursor: pointer;
  user-select: none;
}
.confirm-allow-box {
  accent-color: var(--color-primary, #2f6f4f);
  cursor: pointer;
}

/* M9 历史授权卡（只读）：灰调、无交互 */
.confirm-card.history {
  opacity: 0.75;
  pointer-events: none;
}
.confirm-card.history .confirm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* M7 权限档位选择条 */
.perm-mode-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 860px;
  margin: 0 auto 8px;
  padding: 0 4px;
}
.perm-label {
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-tertiary, #9c958d);
  flex-shrink: 0;
}
.perm-seg {
  display: inline-flex;
  border: 1px solid var(--color-border, #d4cdc2);
  border-radius: 999px;
  overflow: hidden;
  background: var(--color-bg-elevated, #fff);
}
.perm-opt {
  padding: 3px 14px;
  font-size: var(--fs-caption, 12px);
  color: var(--color-text-secondary, #5c564f);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.perm-opt + .perm-opt {
  border-left: 1px solid var(--color-border, #d4cdc2);
}
.perm-opt:hover:not(:disabled):not(.active) {
  color: var(--color-primary, #2f6f4f);
}
.perm-opt.active {
  background: var(--color-primary, #2f6f4f);
  color: #fff;
}
.perm-opt:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
/* auto 档警示：分段器描红 + 常驻风险提示 */
.perm-seg.danger {
  border-color: var(--color-danger, #a85a4a);
  box-shadow: 0 0 0 1px var(--color-danger, #a85a4a);
}
.perm-warn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-caption, 12px);
  color: var(--color-danger, #a85a4a);
  font-weight: 600;
}

/* M7 批量授权卡逐项结果 */
.confirm-batch-result { flex-direction: column; align-items: stretch; gap: 8px; }
.batch-result-list {
  border-top: 1px dashed var(--color-divider, #eae6df);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
}
.batch-result-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: var(--fs-caption, 12px);
}
.batch-result-item .batch-result-title { color: var(--color-text-secondary, #5c564f); flex-shrink: 0; }
.batch-result-item .batch-result-msg { color: var(--color-success, #5d8a6b); text-align: right; word-break: break-all; }
.batch-result-item.fail .batch-result-msg { color: var(--color-danger, #a85a4a); }

.composer-box {
  position: relative;
  max-width: 860px;
  margin: 0 auto;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  transition: border-color var(--duration-fast) var(--ease-soft),
              box-shadow var(--duration-fast) var(--ease-soft);
}
.composer-box:focus-within,
.composer-box.active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-light);
}
.composer-input :deep(.el-textarea__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent;
  padding: var(--space-3) var(--space-4);
  padding-right: 56px;             /* 给发送按钮留位 */
  font-size: var(--fs-body);
  line-height: var(--lh-normal);
  resize: none;
  box-shadow: none;
}
.composer-btn {
  position: absolute;
  right: 10px;
  bottom: 10px;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  transition: all var(--duration-fast) var(--ease-soft);
}
.composer-btn.send {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}
.composer-btn.send:hover { background: var(--color-primary-hover); transform: scale(1.05); }
.composer-btn.stop {
  background: var(--color-danger);
  color: #fff;
}
.composer-btn.stop:hover { transform: scale(1.05); }
/* 语音输入麦克风：位于发送按钮左侧；录音中红色呼吸提示；转写中转圈禁点 */
.composer-btn.mic {
  right: 56px;
  background: var(--color-bg-subtle, #f5f5f5);
  color: var(--color-text-secondary);
}
.composer-btn.mic:hover { color: var(--color-primary); }
.composer-btn.mic.recording {
  background: var(--color-danger);
  color: #fff;
  animation: asr-pulse 1.2s ease-in-out infinite;
}
@keyframes asr-pulse { 50% { opacity: 0.7; } }
/* 有麦克风按钮时输入文本多让出一格，避免文字压在按钮下 */
.composer-box.has-mic .composer-input :deep(.el-textarea__inner) {
  padding-right: 100px;
}
.asr-tip { color: var(--color-danger); }
.composer-tip {
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: var(--space-2);
}

/* M11：授权等待中的输入区阻塞态（ZCode 同款语义） */
.composer-box.blocked { opacity: 0.55; }
.composer-blocked-tip {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--color-warning, #a8843c);
}

/* 响应式 */
@media (max-width: 768px) {
  .ai-page { grid-template-columns: 1fr; grid-template-rows: 1fr; }
  .conv-side { display: none; }
  .msg-inner { padding: var(--space-4) var(--space-3) 0; }
  .composer { padding: var(--space-2) var(--space-3) var(--space-3); }
  .user-col { max-width: 88%; }
}
</style>
