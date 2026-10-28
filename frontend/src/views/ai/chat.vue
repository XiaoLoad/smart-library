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

            <!-- 用户消息：右侧气泡（data-anchor 供对话锚点导航定位与判定） -->
            <div v-if="m.role === 'user'" class="msg-row user" :data-anchor="String(m.id)">
              <div class="user-bubble">{{ m.content }}</div>
            </div>

            <!-- 助手消息：左侧头像 + 空气化排版 -->
            <div v-else class="msg-row assistant">
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

                <!-- M4：Agent 决策时间线
                     把「思考旁白 → 工具调用 → 授权请求」按实际发生顺序串成一条链，
                     让 Agent 的规划与自主决策过程可见（评审考察点：Agent 是否具备规划能力）。
                     工具卡片沿用原有 pill 形态，thought 用左侧竖线旁白，confirm 用授权卡 -->
                <div v-if="m.timeline && m.timeline.length" class="timeline">
                  <!-- 多工具串联计数：调用 ≥ 2 个时显示链路概览 -->
                  <div v-if="m.toolEvents.length >= 2" class="tool-chain-head">
                    <el-icon class="chain-icon"><Connection /></el-icon>
                    <span>本次共调用 {{ m.toolEvents.length }} 个工具</span>
                  </div>

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
                          <AiGenUi :snapshot="parseSnapshot(node.card.data)" />
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
                    <span v-if="card._fromHistory" class="gen-ui-time" :title="'查询时点：' + formatSnapTime(card._snapshotTime)">
                      {{ formatSnapTime(card._snapshotTime) }} 查询
                    </span>
                  </div>
                  <AiGenUi :snapshot="parseSnapshot(card.data)" />
                </div>

                <!-- 写操作授权卡：AI 发起的写操作被代码级门禁拦截，等待用户授权。
                     形态刻意区别于工具卡片（卡片 vs pill），避免被当成一次普通查询结果 -->
                <div v-if="m.confirmCard" class="confirm-card" :class="m.confirmCard.state">
                  <div class="confirm-head">
                    <span class="confirm-eyebrow">
                      <el-icon><Lock /></el-icon>
                      <span>{{ confirmEyebrow(m.confirmCard.state) }}</span>
                    </span>
                  </div>
                  <div class="confirm-title">{{ m.confirmCard.title }}</div>
                  <div v-if="m.confirmCard.rows && m.confirmCard.rows.length" class="confirm-rows">
                    <div v-for="(r, ri) in m.confirmCard.rows" :key="ri" class="confirm-row">
                      <span class="confirm-k">{{ r.key }}</span>
                      <span class="confirm-v mono">{{ r.value }}</span>
                    </div>
                  </div>
                  <div v-if="m.confirmCard.state === 'pending'" class="confirm-actions">
                    <button class="confirm-btn primary" @click="onConfirmWrite(m.confirmCard)">
                      确认办理
                    </button>
                    <button class="confirm-btn" @click="onCancelWrite(m.confirmCard)">取消</button>
                    <span class="confirm-ttl">{{ m.confirmCard.remain ?? m.confirmCard.ttlSeconds }} 秒内有效</span>
                  </div>
                  <div v-else-if="m.confirmCard.state === 'submitting'" class="confirm-actions">
                    <span class="confirm-state">
                      <el-icon class="is-loading"><Loading /></el-icon>
                      <span>正在办理…</span>
                    </span>
                  </div>
                  <div v-else-if="m.confirmCard.state === 'confirmed'" class="confirm-actions">
                    <span class="confirm-state ok">
                      <el-icon><Check /></el-icon>
                      <span>已确认并执行</span>
                    </span>
                  </div>
                  <div v-else-if="m.confirmCard.state === 'cancelled'" class="confirm-actions">
                    <span class="confirm-state muted">
                      <el-icon><Close /></el-icon>
                      <span>已取消本次操作</span>
                    </span>
                  </div>
                  <div v-else-if="m.confirmCard.state === 'expired'" class="confirm-actions">
                    <span class="confirm-state err">
                      <el-icon><Warning /></el-icon>
                      <span>{{ m.confirmCard.error || '授权已失效，请重新发起' }}</span>
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
             切换即时生效并持久到会话，读者端不显示 auto 选项 -->
        <div v-if="currentConvId" class="perm-mode-bar">
          <span class="perm-label">写操作权限</span>
          <div class="perm-seg">
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
        </div>
        <div class="composer-box" :class="{ active: input }">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            placeholder="输入您的问题，Enter 发送，Shift+Enter 换行"
            resize="none"
            class="composer-input"
            @keydown.enter.exact.prevent="send"
          />
          <button
            class="composer-btn"
            :class="thinking ? 'stop' : 'send'"
            :title="thinking ? '停止生成' : '发送'"
            @click="thinking ? stopGenerate() : send()"
          >
            <el-icon><VideoPause v-if="thinking" /><Promotion v-else /></el-icon>
          </button>
        </div>
        <p class="composer-tip">AI 助手基于馆藏数据回答，内容仅供参考。</p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import {
  Plus, ChatDotRound, MagicStick, Promotion, Delete,
  VideoPause, Loading, Check, Close, ArrowDownBold, ArrowUpBold, Connection,
  Lock, Warning, DataBoard,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { aiApi } from '@/api'
import { useAiSearchStore } from '@/stores/aiSearch'
import { useUserStore } from '@/stores/user'
import ConversationNav from '@/components/ConversationNav.vue'
import AppIcon from '@/components/AppIcon.vue'
import AiGenUi from '@/components/ai/AiGenUi.vue'

// keep-alive 缓存名单按路由 name 匹配，故显式声明组件名
defineOptions({ name: 'AIChat' })

// 初始化 Markdown 渲染器：启用代码高亮、链接安全属性
const md = new MarkdownIt({
  html: false,        // 不允许内嵌 HTML（防止 XSS）
  breaks: true,       // 单换行转 <br>
  linkify: true,      // 自动识别链接
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang, ignoreIllegals: true }).value}</code></pre>`
      } catch {
        // fallthrough
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
  },
})

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

const renderMarkdown = (content) => {
  if (!content) return ''
  try {
    return md.render(content)
  } catch {
    return md.utils.escapeHtml(content)
  }
}

// 历史/分页消息映射：预渲染 Markdown 为 html 缓存，避免模板内反复重渲染
const enrichMsg = (m) => ({
  ...m,
  html: renderMarkdown(m.content),
  // 工具快照落库后历史消息可重建内联生成式卡片；快照是查询时点数据，历史卡片带时点角标
  toolEvents: parseStoredSnapshots(m.toolSnapshots),
  // 历史消息不持久化思维链（与工具中间态一致），仅本轮流式生成时展示
  reasoningSegments: [],
  reasoningOpen: false,
  reasoningStreaming: false,
  // 参考资料同理：rag 事件只在当轮推送，落库内容不含来源，历史消息无此块
  ragSources: [],
  ragOpenIndex: -1,
  // M4：thought 旁白不落库（与思维链一致），历史消息无时间线
  timeline: [],
})

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
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgListRef = ref(null)

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
  if (mode === permMode.value || !currentConvId.value) return
  try {
    await aiApi.setPermissionMode(currentConvId.value, mode)
    permMode.value = mode
    ElMessage.success(mode === 'confirm' ? '已切换为逐笔确认' : mode === 'batch' ? '已切换为批量授权' : '已切换为自动执行')
  } catch (e) {
    ElMessage.error(e?.message || '权限档位切换失败')
  }
}

// AI 检索联动：与悬浮球面板一致，queryBooks/queryHotBooks 结果广播到 aiSearchStore，
// 图书检索页 watch 到后切换为「AI 检索结果」视图
const aiSearchStore = useAiSearchStore()
const lastUserText = ref('')
// 检索联动候补：本轮工具返回的图书快照先暂存，流结束（done）且本轮无写操作时才广播——
// 借书等写流程中模型也会先调 queryBooks 拿书名，那是流程中间步骤，不应联动图书页筛选
let pendingAiSearch = null
// 本轮是否出现写操作授权（confirm 事件，仅写操作会触发门禁）——出现则本轮不广播检索联动
let turnHasWrite = false
// 写意图关键词：用户原话带借/还/续借等写意图时同样不广播——模型在借书前常先查库存、
// 确认版本（不调写工具、无 confirm 事件），如「请先选定一版，我再提交借阅申请」
const WRITE_INTENT_RE = /(借阅|帮我借|想借|要借|借下|借一下|借一本|借这本|借了|续借|归还|还书|罚款)/
let turnWriteIntent = false
// 历史分页加载状态
const oldestLoadedId = ref(null)   // 当前已加载消息中最老的 id（更早一页的游标）
const loadedCount = ref(0)         // 已加载消息数
const totalMessages = ref(0)       // 会话消息总数
const hasMore = ref(false)         // 是否还有更早消息
const loadingOlder = ref(false)    // 防重复拉取守卫
// 当前流式请求的 AbortController（用于取消）
let abortController = null
// 当前流式回复的助手消息引用（send 中创建，stopGenerate/catch/finally 中复用）
let assistantMsg = null

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
    const data = await aiApi.conversationList()
    conversations.value = data.list || []
    if (conversations.value.length) {
      await switchConv(conversations.value[0].id)
    }
  } catch (e) {
    // 加载失败静默
  }
}

const switchConv = async (id) => {
  if (thinking.value) return // 流式输出中不切换
  currentConvId.value = id
  isAutoFollow.value = true
  // M7：加载该会话的权限档位（切会话即切档位视图；接口失败按默认逐笔确认显示）
  const convMeta = conversations.value.find((c) => c.id === id)
  permMode.value = convMeta?.permissionMode || 'confirm'
  try {
    const data = await aiApi.messageList(id)
    // 后端返回最新一页（id DESC），倒转为正序后再渲染
    const page = (data.list || []).map(enrichMsg).reverse()
    messages.value = page
    loadedCount.value = page.length
    totalMessages.value = data.total || page.length
    oldestLoadedId.value = page.length ? page[0].id : null
    hasMore.value = loadedCount.value < totalMessages.value
  } catch (e) {
    messages.value = []
    hasMore.value = false
  }
  await scrollToBottomForce()
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
    if (!older.length) {
      hasMore.value = false
      return
    }
    messages.value = [...older, ...messages.value]
    loadedCount.value += older.length
    oldestLoadedId.value = older[0].id
    hasMore.value = loadedCount.value < (data.total || totalMessages.value)
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
  if (thinking.value) return
  try {
    const conv = await aiApi.createConversation()
    conversations.value.unshift(conv)
    currentConvId.value = conv.id
    messages.value = []
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
    await aiApi.deleteConversation(id)
    const idx = conversations.value.findIndex((c) => c.id === id)
    if (idx >= 0) conversations.value.splice(idx, 1)
    if (currentConvId.value === id) {
      currentConvId.value = null
      messages.value = []
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

// 确认执行：回传令牌与会话 ID，参数由服务端保存，客户端无法篡改
const onConfirmWrite = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  try {
    const res = await aiApi.confirmWrite(card.token, card.tool, card.conversationId)
    card.state = 'confirmed'
    card.result = res
    // 依据工具真实返回给出简短回执；完整回答交由模型在下一轮生成
    const tip = res?.message || '操作已完成'
    appendAssistantText(tip)
  } catch (e) {
    // 失败分类：令牌类失效（过期/已被处理/不匹配）→ expired；业务错误（如库存不足）→ failed。
    // 令牌一次性，失败后原卡无法重试，两种状态都只说明结果，不一律谎称过期
    const message = e?.message || '授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
  }
}

// 用户取消：卡片原地转为静默状态，不跳变
const onCancelWrite = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
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

// 全部确认：只回传批次令牌，服务端按保存的批次顺序逐项执行
const onConfirmBatch = async (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'submitting'
  try {
    const res = await aiApi.confirmBatchWrite(card.token, card.conversationId)
    card.state = 'confirmed'
    card.result = res
    appendAssistantText(res?.message || '批量办理完成')
  } catch (e) {
    const message = e?.message || '批量授权已失效，请重新发起该操作'
    const tokenInvalid = /失效|过期|已被处理|重新发起/.test(message)
    card.state = tokenInvalid ? 'expired' : 'failed'
    card.error = message
  }
}

// 取消本批：后端批次到期自动失效，本地置灰即可
const onCancelBatch = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'cancelled'
}

// 授权过期：卡片灰态保留，说明发生过什么而非凭空消失
const onConfirmExpired = (card) => {
  if (!card || card.state !== 'pending') return
  stopConfirmCountdown(card)
  card.state = 'expired'
  card.error = '授权已过期，请重新发起该操作'
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

/** 在当前助手气泡追加一句文本 */
const appendAssistantText = (text) => {
  const msg = assistantMsg
  if (!msg) return
  msg.content = msg.content ? `${msg.content}\n\n${text}` : text
  msg.html = renderMarkdown(msg.content)
  scrollToBottom()
}

/** 停止生成：中断当前 SSE 请求 */
const stopGenerate = () => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  thinking.value = false
}

const send = async () => {
  if (thinking.value) return
  if (!input.value.trim()) return
  const content = input.value.trim()
  input.value = ''
  lastUserText.value = content
  // 新一轮：重置检索联动候补与写操作标记
  pendingAiSearch = null
  turnHasWrite = false
  turnWriteIntent = WRITE_INTENT_RE.test(content)

  // 无会话时先创建
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

  const convId = currentConvId.value
  const conv = conversations.value.find((c) => c.id === convId)
  const isFirstMsg = !conv || conv.title === '新对话' || !conv.title

  // 推送用户消息
  messages.value.push({
    id: Date.now(),
    conversationId: convId,
    role: 'user',
    content,
    createTime: new Date().toISOString(),
  })
  isAutoFollow.value = true
  showJumpBtn.value = false
  await scrollToBottom()
  thinking.value = true
  scrollToBottom() // 思考占位渲染增高，保持视口在底部

  // SSE 流式请求
  abortController = new AbortController()

  // 立即创建助手消息气泡（pending=true 显示「正在思考…」跳动），
  // 不等首个 SSE 事件——避免首事件前的空窗期没有任何反馈
  assistantMsg = reactive({
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
    // 写操作授权卡（SSE confirm 事件）
    confirmCard: null,
    // M7 批量授权卡（SSE batch_confirm 事件，batch 档位）
    batchConfirmCard: null,
    // M4：Agent 决策时间线（thought 旁白节点），与工具卡片交替构成决策链视图
    timeline: [],
    createTime: new Date().toISOString(),
  })
  messages.value.push(assistantMsg)
  scrollToBottom()

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
      body: JSON.stringify({ conversationId: convId, content }),
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
      // 首个实际事件到达时关闭单独的「思考中」占位
      if (thinking.value) {
        thinking.value = false
      }
      return assistantMsg
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
          scrollToBottom()
        }
      } else if (event === 'thinking') {
        // 深度思考思维链增量：流式追加到当前思考段，面板保持展开
        const msg = ensureAssistantMsg()
        msg.pending = false
        if (data) {
          if (!msg._reasonSegOpen) {
            // 上一段思维链已被工具调用/正文截断 → 新开一段（多轮工具调用场景）
            msg.reasoningSegments.push('')
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
          scrollToBottom()
        }
      } else if (event === 'rag') {
        // 知识库来源：在调用 LLM 之前推送（早于 delta），气泡内先出「参考资料」条
        const msg = ensureAssistantMsg()
        msg.pending = false
        try {
          const list = JSON.parse(data)
          if (Array.isArray(list)) {
            for (const item of list) {
              const docTitle = item?.docTitle || '未命名文档'
              const snippet = item?.snippet || ''
              // 多轮工具调用可能重复检索到同一片段，按「标题 + 摘要」去重，避免药丸堆积
              const dup = msg.ragSources.some(
                (s) => s.docTitle === docTitle && s.snippet === snippet
              )
              if (!dup) msg.ragSources.push({ docTitle, snippet })
            }
          }
        } catch {
          // 忽略解析失败的 rag 事件
        }
      } else if (event === 'delta') {
        const msg = ensureAssistantMsg()
        msg.pending = false
        // 正文开始：思维链阶段结束，停止耗时并自动折叠面板（DeepSeek 式交互）
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content += data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'reset') {
        // 进入工具调用轮：清空工具调用前的过渡文本（正文只保留最终回答，
        // 工具调用过程由工具卡片承载）；本段思维链结束，停止耗时并折叠面板
        const msg = ensureAssistantMsg()
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        msg.content = ''
        msg.html = ''
      } else if (event === 'final') {
        // 后端完成回答后的最终文本（已剔除误输出的工具调用代码），整体替换流式拼接内容
        const msg = ensureAssistantMsg()
        msg.content = data
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
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
        scrollToBottom()
      } else if (event === 'done') {
        thinking.value = false
        // 流结束：本轮既无写操作（confirm 事件）也无写意图（用户原话）才广播检索候补到馆藏图书页
        if (pendingAiSearch) {
          if (!turnHasWrite && !turnWriteIntent) {
            aiSearchStore.setResult(pendingAiSearch.query, pendingAiSearch.items, pendingAiSearch.total)
          }
          pendingAiSearch = null
        }
        turnHasWrite = false
        turnWriteIntent = false
        if (assistantMsg) {
          assistantMsg.pending = false
          stopReasonTimer(assistantMsg)
          assistantMsg._reasonSegOpen = false
          assistantMsg.reasoningStreaming = false
          assistantMsg.reasoningOpen = false
        }
        // 流结束滚动定位：本轮有内联生成式卡片时滚到本轮第一张卡片，否则维持滚底
        scrollToFirstGenCard(assistantMsg).then((hit) => {
          if (!hit) scrollToBottom()
        })
        if (isFirstMsg && conv) {
          conv.title = content.length > 20 ? content.substring(0, 20) + '…' : content
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
          msg.confirmCard = {
            token: info.token,
            tool: info.tool,
            title: info.title || '写操作授权',
            rows: Array.isArray(info.rows) ? info.rows : [],
            ttlSeconds: info.ttlSeconds || 60,
            // 确认请求回传，供服务端校验令牌与会话归属一致
            conversationId: convId,
            // pending: 等待确认 / confirmed: 已确认 / cancelled: 用户取消 / expired: 已过期 / failed: 办理失败
            state: 'pending',
            result: null,
            error: '',
          }
          // M4：授权请求记入时间线，与旁白、工具卡片构成完整决策链
          msg.timeline.push({ kind: 'confirm', tool: info.tool || '' })
          msg.content = msg.content || '请确认上方授权请求后继续。'
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.confirmCard)
          scrollToBottom()
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
            // pending: 等待确认 / submitting / confirmed / cancelled / expired / failed
            state: 'pending',
            result: null,
            error: '',
          }
          msg.timeline.push({ kind: 'confirm', tool: 'batch:' + (info.count || 0) })
          msg.content = msg.content || `本轮共 ${info.count || 0} 项写操作待授权，请在下方批量卡上一并确认。`
          msg.html = renderMarkdown(msg.content)
          startConfirmCountdown(msg.batchConfirmCard)
          scrollToBottom()
        } catch {
          // 解析失败时不影响对话
        }
      } else if (event === 'degrade') {
        // 工具循环超限降级：非报错样式，提示已完成部分 + 建议继续追问
        const msg = ensureAssistantMsg()
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
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
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
      } else if (event === 'error') {
        thinking.value = false
        const msg = ensureAssistantMsg()
        msg.pending = false
        stopReasonTimer(msg)
        msg._reasonSegOpen = false
        msg.reasoningStreaming = false
        msg.reasoningOpen = false
        // 已有正文时保留原内容，错误说明追加其后（避免中途出错抹掉已流出的回答）
        const errTip = `⚠️ ${data || 'AI 请求出错'}`
        msg.content = msg.content ? `${msg.content}\n\n${errTip}` : errTip
        msg.html = renderMarkdown(msg.content)
        scrollToBottom()
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
  } catch (e) {
    thinking.value = false
    if (e.name === 'AbortError') {
      // 用户主动停止：保留已生成内容
      return
    }
    ElMessage.error(e.message || 'AI 请求失败')
    if (assistantMsg) {
      // 已有正文时保留原内容，错误说明追加其后
      const errTip = `⚠️ ${e.message || 'AI 请求失败'}`
      assistantMsg.content = assistantMsg.content ? `${assistantMsg.content}\n\n${errTip}` : errTip
      assistantMsg.html = renderMarkdown(assistantMsg.content)
    }
  } finally {
    abortController = null
    // 兜底复位：服务端超时/断网导致流关闭但 done 事件未到达时，
    // 按钮仍停在「停止生成」态——任何退出路径最终都要回到可输入状态
    thinking.value = false
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
    }
  }
}

const formatTime = (t) => {
  if (!t) return ''
  return t.substring(5, 10)
}

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
  messages.value.forEach((m) => {
    stopConfirmCountdown(m.confirmCard)
    stopConfirmCountdown(m.batchConfirmCard)
  })
})

onMounted(loadConversations)
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
.user-bubble {
  max-width: 78%;
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

/* 思维链正文：小号次级文字，限高内滚，避免超长推理撑爆对话区 */
.reason-body {
  padding: 0 12px 10px 38px;
  max-height: 240px;
  overflow-y: auto;
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

/* Markdown 排版 */
.msg-content.markdown :deep(p) { margin: 0 0 var(--space-2); }
.msg-content.markdown :deep(p:last-child) { margin-bottom: 0; }
.msg-content.markdown :deep(h1),
.msg-content.markdown :deep(h2),
.msg-content.markdown :deep(h3),
.msg-content.markdown :deep(h4) {
  margin: var(--space-3) 0 var(--space-2);
  font-weight: 600;
  line-height: 1.4;
  font-family: var(--font-serif);
}
.msg-content.markdown :deep(h1) { font-size: 1.35em; }
.msg-content.markdown :deep(h2) { font-size: 1.2em; }
.msg-content.markdown :deep(h3) { font-size: 1.08em; }
.msg-content.markdown :deep(ul),
.msg-content.markdown :deep(ol) { margin: var(--space-2) 0; padding-left: 1.5em; }
.msg-content.markdown :deep(li) { margin: 4px 0; }
.msg-content.markdown :deep(code) {
  background: var(--color-bg-muted);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 0.88em;
}
.msg-content.markdown :deep(pre) {
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  overflow-x: auto;
  margin: var(--space-2) 0;
}
.msg-content.markdown :deep(pre code) { background: transparent; padding: 0; font-size: 0.85em; line-height: 1.5; }
.msg-content.markdown :deep(table) {
  border-collapse: collapse;
  margin: var(--space-2) 0;
  width: 100%;
  font-size: var(--fs-small);
}
.msg-content.markdown :deep(th),
.msg-content.markdown :deep(td) {
  border: 1px solid var(--color-border);
  padding: 6px 12px;
  text-align: left;
}
.msg-content.markdown :deep(th) { background: var(--color-bg-subtle); font-weight: 600; }
.msg-content.markdown :deep(blockquote) {
  border-left: 3px solid var(--color-primary);
  padding-left: var(--space-3);
  margin: var(--space-2) 0;
  color: var(--color-text-secondary);
}
.msg-content.markdown :deep(a) { color: var(--color-primary); text-decoration: none; }
.msg-content.markdown :deep(a:hover) { text-decoration: underline; }
.msg-content.markdown :deep(strong) { font-weight: 600; color: var(--color-text); }

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
.composer-tip {
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: var(--space-2);
}

/* 响应式 */
@media (max-width: 768px) {
  .ai-page { grid-template-columns: 1fr; grid-template-rows: 1fr; }
  .conv-side { display: none; }
  .msg-inner { padding: var(--space-4) var(--space-3) 0; }
  .composer { padding: var(--space-2) var(--space-3) var(--space-3); }
  .user-bubble { max-width: 88%; }
}
</style>
