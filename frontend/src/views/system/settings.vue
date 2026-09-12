<template>
  <div class="page settings-page">
    <header class="page-header">
      <span class="eyebrow">管理 · SETTINGS</span>
      <h1>系统设置</h1>
      <p class="desc">AI 渠道、借阅规则、账户安全与 AI 人设，集中配置。</p>
    </header>

    <div class="settings-layout">
      <!-- 左侧 · 设置目次 -->
      <aside class="settings-nav">
        <div class="nav-toc">设置目次</div>
        <template v-for="g in navGroups" :key="g.title">
          <div class="nav-group-title">
            <span>{{ g.title }}</span>
            <span class="en">{{ g.en }}</span>
          </div>
          <button
            v-for="s in g.items"
            :key="s.key"
            type="button"
            class="nav-item"
            :class="{ active: active === s.key }"
            @click="onNav(s.key)"
          >
            <AppIcon :name="s.icon" :size="16" />
            <span class="nav-label">{{ s.label }}</span>
            <span class="nav-en">{{ s.en }}</span>
          </button>
        </template>
      </aside>

      <!-- 右侧 · 设置面板 -->
      <div class="settings-body">
        <!-- ===== 账户安全 ===== -->
        <section v-show="active === 'account'" class="setting-panel">
          <div class="card panel-card fade-in">
            <div class="card-head">
              <div class="card-head__icon"><AppIcon name="lock" :size="22" /></div>
              <div>
                <h2 class="card-title">账户安全</h2>
                <p class="card-sub">为管理员账户设置新的登录密码，建议定期更换以保障安全。</p>
              </div>
            </div>

            <ChangePasswordForm />
          </div>
        </section>

        <!-- ===== AI 渠道 ===== -->
        <section v-show="active === 'ai'" class="setting-panel">
          <div class="card panel-card fade-in">
            <div class="card-head card-head--between">
              <div class="card-head-main">
                <div class="card-head__icon"><AppIcon name="cpu" :size="22" /></div>
                <div>
                  <h2 class="card-title">AI 服务渠道</h2>
                  <p class="card-sub">添加多个 OpenAI 兼容服务商渠道，系统按列表顺序使用第一个启用中的渠道。</p>
                </div>
              </div>
              <el-button type="primary" class="head-action" @click="openAdd">
                <AppIcon name="plus" :size="15" />新增渠道
              </el-button>
            </div>

            <div class="channel-hint">
              <AppIcon name="bolt" :size="15" />
              <span>
                渠道按从上到下的顺序作为优先级，停用的渠道自动跳过，可用右侧箭头调整顺序。
                模型列表在添加/编辑时从服务商<b>实时拉取</b>，系统不内置固定模型选项。
              </span>
            </div>

            <!-- 加载失败：与"真无渠道"区分，避免伪装成空状态导致重复添加 -->
            <div v-if="!channelsLoading && channelsError" class="channel-empty is-error">
              <AppIcon name="bolt" :size="30" />
              <p class="empty-title">渠道列表加载失败</p>
              <span class="empty-desc">后端服务可能未启动、正在重启，或当前账号无管理员权限。请确认后端正常后重试。</span>
              <el-button type="primary" :loading="channelsLoading" @click="loadChannels">
                <AppIcon name="check" :size="15" />重新加载
              </el-button>
            </div>

            <!-- 空状态 -->
            <div v-else-if="!channelsLoading && channels.length === 0" class="channel-empty">
              <AppIcon name="cpu" :size="30" />
              <p class="empty-title">尚未配置 AI 渠道</p>
              <span class="empty-desc">添加 DeepSeek、通义千问、Kimi 等 OpenAI 兼容渠道后，AI 助手即可对话</span>
              <el-button type="primary" @click="openAdd">
                <AppIcon name="plus" :size="15" />添加第一个渠道
              </el-button>
            </div>

            <!-- 渠道列表 -->
            <div v-loading="channelsLoading" class="channel-list">
              <div
                v-for="(ch, idx) in channels"
                :key="ch.id"
                class="channel-card"
                :class="{ 'is-disabled': !ch.enabled }"
              >
                <span
                  class="status-dot"
                  :class="ch.active ? 'dot-active' : ch.enabled ? 'dot-standby' : 'dot-off'"
                ></span>

                <div class="channel-icon"><AppIcon name="cpu" :size="18" /></div>

                <div class="channel-info">
                  <div class="channel-name-row">
                    <span class="channel-name">{{ ch.name }}</span>
                    <span
                      class="badge"
                      :class="ch.active ? 'badge-active' : ch.enabled ? 'badge-standby' : 'badge-off'"
                    >{{ ch.active ? '使用中' : ch.enabled ? '备用' : '已停用' }}</span>
                  </div>
                  <div class="channel-meta">
                    <span class="provider-tag">{{ providerLabel(ch.provider) }}</span>
                    <span class="model-tag"><EllipsisText :content="ch.model" class="model-tag-text" /></span>
                    <span class="key-tag" :class="ch.apiKeySet ? 'is-ok' : 'is-warn'">
                      {{ ch.apiKeySet ? 'API Key 已配置' : 'API Key 未配置' }}
                    </span>
                    <span v-if="ch.thinkingEnabled" class="think-tag" title="该渠道已开启深度思考">
                      <AppIcon name="sparkles" :size="12" />深度思考
                    </span>
                  </div>
                  <EllipsisText :content="ch.endpoint" class="channel-endpoint" />
                </div>

                <div class="channel-actions">
                  <div class="order-btns">
                    <el-button text :disabled="idx === 0" title="上移（提高优先级）" @click="moveChannel(ch, -1)">
                      <AppIcon name="arrow-up" :size="15" />
                    </el-button>
                    <el-button
                      text
                      :disabled="idx === channels.length - 1"
                      title="下移（降低优先级）"
                      @click="moveChannel(ch, 1)"
                    >
                      <AppIcon name="arrow-down" :size="15" />
                    </el-button>
                  </div>
                  <el-switch
                    :model-value="ch.enabled"
                    title="启用 / 停用"
                    @change="(v) => onToggle(ch, v)"
                  />
                  <el-button text title="测试连接并拉取模型" :loading="testingId === ch.id" @click="onTest(ch)">
                    <AppIcon name="bolt" :size="15" />
                  </el-button>
                  <el-button text title="编辑" @click="openEdit(ch)">
                    <AppIcon name="pencil" :size="15" />
                  </el-button>
                  <el-button text title="删除" class="danger-btn" @click="onRemove(ch)">
                    <AppIcon name="trash" :size="15" />
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- ===== 借阅规则 ===== -->
        <section v-show="active === 'rules'" class="setting-panel">
          <!-- 逾期罚款 -->
          <div class="card panel-card fade-in">
            <div class="card-head">
              <div class="card-head__icon icon-coin"><AppIcon name="coin" :size="22" /></div>
              <div>
                <h2 class="card-title">逾期罚款</h2>
                <p class="card-sub">图书逾期归还时，自应还日期起按自然日计收滞纳金，归还结算时自动生成罚款单。</p>
              </div>
            </div>
            <div class="fine-row">
              <el-input-number
                v-model="fineRate"
                :min="0"
                :max="10"
                :step="0.1"
                :precision="2"
                controls-position="right"
                class="fine-input"
              />
              <span class="fine-unit">元 / 天</span>
              <el-button type="primary" :loading="fineSaving" @click="saveFineRate">保存费率</el-button>
            </div>
            <p class="rule-effect-hint">设为 0 表示免收滞纳金；新费率对保存后新结算的罚款生效，已生成的罚款单不受影响。</p>
          </div>

          <!-- 借阅额度 -->
          <div class="card panel-card fade-in">
            <div class="card-head">
              <div class="card-head__icon"><AppIcon name="book" :size="22" /></div>
              <div>
                <h2 class="card-title">借阅额度</h2>
                <p class="card-sub">按读者身份设定可借册数、借阅天数与续借规则；管理员账号不可借阅，无额度项。</p>
              </div>
            </div>

            <div v-loading="limitsLoading" class="limit-list">
              <div v-for="role in roles" :key="role.userType" class="limit-role">
                <div class="limit-role-head">
                  <span class="role-name">{{ role.label }}</span>
                  <span class="role-tag">{{ role.userType === 1 ? '教师身份 · userType 1' : '学生身份 · userType 2' }}</span>
                </div>
                <div class="limit-fields">
                  <div v-for="f in LIMIT_FIELDS" :key="f.key" class="limit-field">
                    <label>{{ f.label }}</label>
                    <div class="limit-input-wrap">
                      <el-input-number
                        v-model="role[f.key]"
                        :min="f.min"
                        :max="f.max"
                        :step="1"
                        controls-position="right"
                        size="small"
                      />
                      <span class="limit-unit">{{ f.unit }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="limit-footer">
              <p class="rule-effect-hint">
                规则保存后即时生效，仅影响新办理的借阅申请、取书与续借；进行中的借阅单仍按原应还日期执行。
              </p>
              <el-button type="primary" :loading="limitsSaving" @click="saveLimits">保存全部规则</el-button>
            </div>
          </div>
        </section>

        <!-- ===== AI 人设 ===== -->
        <section v-show="active === 'prompt'" class="setting-panel">
          <div class="card panel-card fade-in">
            <div class="card-head">
              <div class="card-head__icon"><AppIcon name="sparkles" :size="22" /></div>
              <div>
                <h2 class="card-title">AI 助手人设</h2>
                <p class="card-sub">自定义 AI 助手的语气、人设与称呼风格，保存后下一轮对话即时生效。</p>
              </div>
            </div>

            <div class="prompt-notice">
              <AppIcon name="sparkles" :size="15" />
              <div>
                <p>此段<b>仅控制人设与语气</b>：正文中的 <code>{username}</code> 会自动替换为用户真实姓名。</p>
                <p>工具调用、数据完整性、权限隔离等<b>核心业务规则由系统内置</b>，不会被此内容覆盖或关闭。</p>
              </div>
            </div>

            <div v-loading="promptLoading" class="prompt-editor">
              <el-input
                v-model="promptContent"
                type="textarea"
                :rows="16"
                placeholder="在此书写 AI 助手的人设与语气…"
              />
            </div>
            <div class="prompt-footer">
              <span class="prompt-count">{{ promptContent.length }} 字</span>
              <div>
                <el-button :loading="promptLoading" @click="loadPrompt">重新加载</el-button>
                <el-button type="primary" :loading="promptSaving" @click="savePrompt">保存并生效</el-button>
              </div>
            </div>
          </div>
        </section>

        <!-- ===== 催还通知 · 渠道配置 ===== -->
        <section v-show="active === 'notify'" class="setting-panel">
          <!-- 催还通知规则 -->
          <div class="card panel-card fade-in">
            <div class="card-head card-head--between">
              <div class="card-head-main">
                <div class="card-head__icon"><AppIcon name="bell" :size="22" /></div>
                <div>
                  <h2 class="card-title">催还通知规则</h2>
                  <p class="card-sub">为即将逾期与不同逾期天数的借阅，分别设置提醒渠道、提前/滞后天数与通知文案。</p>
                </div>
              </div>
              <el-button type="primary" :loading="rulesSaving" @click="saveRules">
                <AppIcon name="check" :size="15" />保存规则
              </el-button>
            </div>

            <div v-loading="rulesLoading" class="rule-list">
              <div v-for="r in rules" :key="r.eventType" class="rule-card" :class="{ 'is-off': !r.enabled }">
                <div class="rule-head">
                  <div class="rule-title">
                    <AppIcon name="bell" :size="15" />
                    <span>{{ eventLabel(r.eventType) }}</span>
                  </div>
                  <div class="rule-head-right">
                    <el-switch v-model="r.enabled" />
                    <el-button text class="rule-toggle" @click="r.open = !r.open">
                      {{ r.open ? '收起模板' : '编辑模板' }}
                      <AppIcon :name="r.open ? 'chevron-up' : 'chevron-down'" :size="13" />
                    </el-button>
                  </div>
                </div>

                <div class="rule-fields">
                  <div class="rule-field">
                    <label>提醒渠道</label>
                    <el-select v-model="r.channel" size="small" class="rule-select">
                      <el-option label="仅站内信" value="INAPP" />
                      <el-option label="仅邮件" value="EMAIL" />
                      <el-option label="站内信 + 邮件" value="BOTH" />
                    </el-select>
                  </div>
                  <div class="rule-field">
                    <label>{{ offsetLabel(r.eventType) }}</label>
                    <el-input-number v-model="r.offsetDays" :step="1" size="small" controls-position="right" />
                    <span class="rule-unit">{{ r.eventType === 'DUE_SOON' ? '天（应还前）' : '天（逾期后）' }}</span>
                  </div>
                </div>

                <div v-if="r.open" class="rule-tpl">
                  <div class="tpl-field">
                    <label>邮件主题</label>
                    <el-input v-model="r.subjectTpl" size="small" placeholder="支持 {readerName}{bookTitle}{dueDate}{borrowNo}{daysOverdue}" />
                  </div>
                  <div class="tpl-field">
                    <label>正文模板</label>
                    <el-input v-model="r.bodyTpl" type="textarea" :rows="3" placeholder="支持占位符 {readerName} {bookTitle} {dueDate} {borrowNo} {daysOverdue}" />
                  </div>
                  <p class="tpl-hint">
                    占位符：<code>{readerName}</code> 读者姓名 · <code>{bookTitle}</code> 书名 ·
                    <code>{dueDate}</code> 应还日期 · <code>{borrowNo}</code> 借阅号 · <code>{daysOverdue}</code> 逾期天数
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- 邮件渠道配置 -->
          <div class="card panel-card fade-in">
            <div class="card-head card-head--between">
              <div class="card-head-main">
                <div class="card-head__icon icon-mail"><AppIcon name="mail" :size="22" /></div>
                <div>
                  <h2 class="card-title">邮件渠道（SMTP）</h2>
                  <p class="card-sub">配置发件邮箱与 SMTP 服务器，催还邮件经此渠道发出；未启用时仅走站内信。</p>
                </div>
              </div>
              <el-button type="primary" :loading="mailSaving" @click="saveMail">
                <AppIcon name="check" :size="15" />保存配置
              </el-button>
            </div>

            <div v-loading="mailLoading" class="mail-form">
              <div class="mail-grid">
                <div class="mail-field">
                  <label>SMTP 主机</label>
                  <el-input v-model="mail.smtpHost" placeholder="如 smtp.qq.com" />
                </div>
                <div class="mail-field">
                  <label>端口</label>
                  <el-input-number v-model="mail.smtpPort" :min="1" :max="65535" size="small" controls-position="right" />
                </div>
                <div class="mail-field">
                  <label>发件账号</label>
                  <el-input v-model="mail.username" placeholder="登录用户名" />
                </div>
                <div class="mail-field">
                  <label>发件密码 / 授权码</label>
                  <el-input v-model="mail.password" type="password" show-password placeholder="SMTP 授权码" />
                </div>
                <div class="mail-field mail-field--wide">
                  <label>发件人地址</label>
                  <el-input v-model="mail.fromAddr" placeholder="读者看到的发件邮箱" />
                </div>
              </div>

              <div class="mail-switches">
                <div class="mail-switch-item">
                  <el-switch v-model="mail.useTls" />
                  <div class="switch-text">
                    <span class="switch-label">使用 TLS 加密</span>
                    <span class="switch-desc">启用 STARTTLS 加密连接（推荐）</span>
                  </div>
                </div>
                <div class="mail-switch-item">
                  <el-switch v-model="mail.enabled" />
                  <div class="switch-text">
                    <span class="switch-label">启用邮件通知</span>
                    <span class="switch-desc">关闭后催还通知仅通过站内信送达</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- ===== 催还通知 · 通知管理 ===== -->
        <section v-show="active === 'notify-manage'" class="setting-panel">
          <div class="card panel-card fade-in">
            <div class="card-head">
              <div class="card-head__icon"><AppIcon name="receipt" :size="22" /></div>
              <div>
                <h2 class="card-title">通知发送管理</h2>
                <p class="card-sub">查看每次催还通知的发送记录，失败或漏发可手动重发；亦可持续触发扫描。</p>
              </div>
            </div>

            <div class="log-toolbar">
              <div class="log-filters">
              <el-select v-model="logFilters.status" placeholder="状态" clearable size="small" class="log-filter">
                <el-option label="全部状态" value="" />
                <el-option label="待发送" value="PENDING" />
                <el-option label="已发送" value="SENT" />
                <el-option label="发送失败" value="FAILED" />
                <el-option label="已跳过" value="SKIPPED" />
              </el-select>
              <el-select v-model="logFilters.channel" placeholder="渠道" clearable size="small" class="log-filter">
                <el-option label="全部渠道" value="" />
                <el-option label="站内信" value="INAPP" />
                <el-option label="邮件" value="EMAIL" />
              </el-select>
              <el-select v-model="logFilters.eventType" placeholder="事件" clearable size="small" class="log-filter">
                <el-option label="全部事件" value="" />
                <el-option label="即将逾期" value="DUE_SOON" />
                <el-option label="逾期第 1 天" value="OVERDUE_D1" />
                <el-option label="逾期第 3 天" value="OVERDUE_D3" />
                <el-option label="逾期第 7 天" value="OVERDUE_D7" />
              </el-select>
              <el-date-picker
                v-model="logDateRange"
                type="daterange"
                size="small"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                class="log-date"
              />
              </div>
              <div class="log-toolbar-right">
                <el-button :loading="logLoading" @click="loadLogs">
                  <AppIcon name="refresh" :size="14" />刷新
                </el-button>
                <el-button type="primary" :loading="triggering" @click="onTrigger">
                  <AppIcon name="bolt" :size="14" />手动触发扫描
                </el-button>
              </div>
            </div>

            <el-table :data="logs" v-loading="logLoading" class="log-table" empty-text="暂无发送记录">
              <el-table-column prop="id" label="ID" width="70" />
              <el-table-column label="事件" width="130">
                <template #default="{ row }">
                  <span class="cell-event">{{ eventLabel(row.eventType) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="渠道" width="90">
                <template #default="{ row }">
                  <el-tag :type="channelTag(row.channel)" size="small" effect="light">{{ channelLabel(row.channel) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="收件人" min-width="140">
                <template #default="{ row }">
                  <span class="cell-email">{{ row.toEmail || (row.channel === 'INAPP' ? '站内信' : '—') }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="statusTag(row.status)" size="small" effect="dark">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="subject" label="主题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="170" />
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button text size="small" :loading="resendingId === row.id" @click="onResend(row)">重发</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="log-pager">
              <el-pagination
                v-model:current-page="logPage"
                v-model:page-size="logSize"
                :total="logTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                background
                @current-change="loadLogs"
                @size-change="loadLogs"
              />
            </div>
          </div>
        </section>
      </div>
    </div>

    <AiChannelDrawer v-model="drawerVisible" :channel="editingChannel" @saved="loadChannels" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/components/AppIcon.vue'
import EllipsisText from '@/components/EllipsisText.vue'
import AiChannelDrawer from '@/components/AiChannelDrawer.vue'
import ChangePasswordForm from '@/components/ChangePasswordForm.vue'
import { systemApi } from '@/api'

/* ===== 目次导航 ===== */
// 按业务域分组：AI 渠道与 AI 人设归到「AI 配置」组，不再被借阅规则隔断
const navGroups = [
  {
    title: '基础设置',
    en: 'GENERAL',
    items: [
      { key: 'account', label: '账户安全', en: 'ACCOUNT', icon: 'lock' },
    ],
  },
  {
    title: 'AI 配置',
    en: 'AI',
    items: [
      { key: 'ai', label: 'AI 渠道', en: 'CHANNELS', icon: 'cpu' },
      { key: 'prompt', label: 'AI 人设', en: 'PERSONA', icon: 'sparkles' },
    ],
  },
  {
    title: '借阅管理',
    en: 'RULES',
    items: [
      { key: 'rules', label: '借阅规则', en: 'RULES', icon: 'ruler' },
    ],
  },
  {
    title: '消息通知',
    en: 'NOTIFY',
    items: [
      { key: 'notify', label: '通知渠道配置', en: 'CHANNELS', icon: 'bell' },
      { key: 'notify-manage', label: '通知管理', en: 'LOGS', icon: 'receipt' },
    ],
  },
]
const active = ref('account')

// 切换目次：AI 渠道面板上次加载失败时自动重试（后端可能刚重启完成）
const onNav = (key) => {
  active.value = key
  if (key === 'ai' && channelsError.value) {
    loadChannels()
  }
  if (key === 'notify-manage') {
    loadLogs()
  }
}

/* ===== AI 渠道 ===== */
const channels = ref([])
const channelsLoading = ref(false)
// 加载失败标记：区分"后端不可达/无权限"与"真的没有渠道"，避免空状态误导
const channelsError = ref(false)
const testingId = ref(null)
const drawerVisible = ref(false)
const editingChannel = ref(null)

const PROVIDER_LABELS = {
  deepseek: 'DeepSeek',
  openai: 'OpenAI',
  qwen: '通义千问',
  moonshot: 'Kimi 月之暗面',
  zhipu: '智谱 AI',
  baichuan: '百川智能',
  minimax: 'MiniMax',
  custom: '自定义接口',
}
const providerLabel = (p) => PROVIDER_LABELS[p] || p || '自定义接口'

const loadChannels = async () => {
  channelsLoading.value = true
  try {
    const data = await systemApi.getChannels()
    channels.value = data.list || []
    channelsError.value = false
  } catch {
    // 请求失败（后端未启动/重启中/403）：标记错误态，由面板展示重试入口，
    // 不能静默成空列表——否则会误导用户重复添加已存在的渠道
    channelsError.value = true
    channels.value = []
  } finally {
    channelsLoading.value = false
  }
}

const openAdd = () => {
  editingChannel.value = null
  drawerVisible.value = true
}

const openEdit = (ch) => {
  editingChannel.value = ch
  drawerVisible.value = true
}

const onToggle = async (ch, enabled) => {
  try {
    await systemApi.toggleChannel({ id: ch.id, enabled })
    ElMessage.success(enabled ? `渠道「${ch.name}」已启用` : `渠道「${ch.name}」已停用`)
    await loadChannels()
  } catch {
    // 失败后重载还原开关状态
    await loadChannels()
  }
}

const moveChannel = async (ch, direction) => {
  try {
    await systemApi.moveChannel({ id: ch.id, direction })
    await loadChannels()
  } catch {
    // 错误已由拦截器提示
  }
}

const onTest = async (ch) => {
  testingId.value = ch.id
  try {
    const data = await systemApi.testChannel({ id: ch.id })
    ElMessage.success(`连接正常，拉取到 ${data.total} 个可用模型`)
  } catch {
    // 错误已由拦截器提示
  } finally {
    testingId.value = null
  }
}

const onRemove = (ch) => {
  ElMessageBox.confirm(
    `确认删除渠道「${ch.name}」？删除后不可恢复，若它是当前生效渠道，系统将自动改用下一优先级渠道。`,
    '删除渠道',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  )
    .then(async () => {
      await systemApi.deleteChannel({ id: ch.id })
      ElMessage.success('渠道已删除')
      await loadChannels()
    })
    .catch(() => {})
}

/* ===== 借阅规则 ===== */
const fineRate = ref(0.5)
const fineSaving = ref(false)
const limitsLoading = ref(false)
const limitsSaving = ref(false)

// 与后端 data.sql 默认值一致，接口返回后覆盖
const roles = ref([
  { userType: 1, label: '教师', maxBooks: 10, loanDays: 60, renewMaxCount: 2, renewDays: 30, pickupDays: 7 },
  { userType: 2, label: '学生', maxBooks: 5, loanDays: 30, renewMaxCount: 1, renewDays: 15, pickupDays: 3 },
])

const LIMIT_FIELDS = [
  { key: 'maxBooks', label: '可借册数', unit: '本', min: 1, max: 99 },
  { key: 'loanDays', label: '借阅天数', unit: '天', min: 1, max: 365 },
  { key: 'renewMaxCount', label: '可续借次数', unit: '次', min: 0, max: 10 },
  { key: 'renewDays', label: '每次续借天数', unit: '天', min: 1, max: 365 },
  { key: 'pickupDays', label: '取书期限', unit: '天', min: 1, max: 60 },
]

const loadLibraryRules = async () => {
  try {
    const data = await systemApi.getLibraryRules()
    if (data.fineRatePerDay != null) {
      fineRate.value = Number(data.fineRatePerDay)
    }
  } catch {
    // 静默
  }
}

const saveFineRate = async () => {
  fineSaving.value = true
  try {
    await systemApi.saveLibraryRules({ fineRatePerDay: fineRate.value })
    ElMessage.success('罚款费率已保存')
  } catch {
    // 错误已由拦截器提示
  } finally {
    fineSaving.value = false
  }
}

const loadLimits = async () => {
  limitsLoading.value = true
  try {
    const list = await systemApi.getBorrowLimits()
    for (const item of list || []) {
      const role = roles.value.find((r) => r.userType === item.userType)
      if (role) {
        role.maxBooks = item.maxBooks
        role.loanDays = item.loanDays
        role.renewMaxCount = item.renewMaxCount
        role.renewDays = item.renewDays
        role.pickupDays = item.pickupDays
      }
    }
  } catch {
    // 静默
  } finally {
    limitsLoading.value = false
  }
}

const saveLimits = async () => {
  limitsSaving.value = true
  try {
    await Promise.all(
      roles.value.map((r) =>
        systemApi.saveBorrowLimit({
          userType: r.userType,
          maxBooks: r.maxBooks,
          loanDays: r.loanDays,
          renewMaxCount: r.renewMaxCount,
          renewDays: r.renewDays,
          pickupDays: r.pickupDays,
        }),
      ),
    )
    ElMessage.success('借阅额度规则已保存')
  } catch {
    // 错误已由拦截器提示
  } finally {
    limitsSaving.value = false
  }
}

/* ===== AI 人设 ===== */
const promptContent = ref('')
const promptLoading = ref(false)
const promptSaving = ref(false)

const loadPrompt = async () => {
  promptLoading.value = true
  try {
    const data = await systemApi.getAiPrompt()
    promptContent.value = data.content || ''
  } catch {
    // 静默
  } finally {
    promptLoading.value = false
  }
}

const savePrompt = async () => {
  if (!promptContent.value.trim()) {
    ElMessage.warning('人设内容不能为空')
    return
  }
  promptSaving.value = true
  try {
    await systemApi.saveAiPrompt({ content: promptContent.value })
    ElMessage.success('AI 人设已保存并即时生效')
  } catch {
    // 错误已由拦截器提示
  } finally {
    promptSaving.value = false
  }
}

/* ===== 催还通知 · 渠道配置 ===== */
const EVENT_LABELS = {
  DUE_SOON: '即将逾期提醒',
  OVERDUE_D1: '逾期第 1 天',
  OVERDUE_D3: '逾期第 3 天',
  OVERDUE_D7: '逾期第 7 天',
}
const CHANNEL_LABELS = { INAPP: '站内信', EMAIL: '邮件', BOTH: '站内+邮件' }
const CHANNEL_TAG = { INAPP: 'info', EMAIL: 'warning', BOTH: 'primary' }
const STATUS_LABELS = { PENDING: '待发送', SENT: '已发送', FAILED: '发送失败', SKIPPED: '已跳过' }
const STATUS_TAG = { PENDING: 'info', SENT: 'success', FAILED: 'danger', SKIPPED: 'warning' }

const eventLabel = (e) => EVENT_LABELS[e] || e || '—'
const channelLabel = (c) => CHANNEL_LABELS[c] || c || '—'
const channelTag = (c) => CHANNEL_TAG[c] || 'info'
const statusLabel = (s) => STATUS_LABELS[s] || s || '—'
const statusTag = (s) => STATUS_TAG[s] || 'info'
const offsetLabel = (e) => (e === 'DUE_SOON' ? '提前天数' : '逾期天数')

// 通知规则
const rules = ref([])
const rulesLoading = ref(false)
const rulesSaving = ref(false)

const loadRules = async () => {
  rulesLoading.value = true
  try {
    const data = await systemApi.getNotificationRules()
    rules.value = (data || []).map((r) => ({ ...r, enabled: r.enabled === 1, open: false }))
  } catch {
    rules.value = []
  } finally {
    rulesLoading.value = false
  }
}

const saveRules = async () => {
  rulesSaving.value = true
  try {
    const payload = rules.value.map((r) => ({
      eventType: r.eventType,
      channel: r.channel,
      enabled: r.enabled ? 1 : 0,
      offsetDays: r.offsetDays,
      subjectTpl: r.subjectTpl,
      bodyTpl: r.bodyTpl,
    }))
    await systemApi.saveNotificationRules(payload)
    ElMessage.success('通知规则已保存')
  } catch {
    // 拦截器已提示
  } finally {
    rulesSaving.value = false
  }
}

// 邮件渠道配置
const mail = ref({ smtpHost: '', smtpPort: 465, username: '', password: '', fromAddr: '', useTls: true, enabled: false })
const mailLoading = ref(false)
const mailSaving = ref(false)

const loadMail = async () => {
  mailLoading.value = true
  try {
    const data = await systemApi.getMailConfig()
    if (data) {
      mail.value = {
        smtpHost: data.smtpHost || '',
        smtpPort: data.smtpPort || 465,
        username: data.username || '',
        password: data.password || '',
        fromAddr: data.fromAddr || '',
        useTls: data.useTls === 1,
        enabled: data.enabled === 1,
      }
    }
  } catch {
    // 静默
  } finally {
    mailLoading.value = false
  }
}

const saveMail = async () => {
  mailSaving.value = true
  try {
    await systemApi.saveMailConfig({
      smtpHost: mail.value.smtpHost,
      smtpPort: mail.value.smtpPort,
      username: mail.value.username,
      password: mail.value.password,
      fromAddr: mail.value.fromAddr,
      useTls: mail.value.useTls ? 1 : 0,
      enabled: mail.value.enabled ? 1 : 0,
    })
    ElMessage.success('邮件配置已保存')
  } catch {
    // 拦截器已提示
  } finally {
    mailSaving.value = false
  }
}

/* ===== 催还通知 · 通知管理 ===== */
const logs = ref([])
const logLoading = ref(false)
const logTotal = ref(0)
const logPage = ref(1)
const logSize = ref(20)
const logFilters = ref({ status: '', channel: '', eventType: '' })
const logDateRange = ref(null)
const resendingId = ref(null)
const triggering = ref(false)

const loadLogs = async () => {
  logLoading.value = true
  try {
    const params = {
      page: logPage.value,
      size: logSize.value,
      status: logFilters.value.status || undefined,
      channel: logFilters.value.channel || undefined,
      eventType: logFilters.value.eventType || undefined,
      startDate: logDateRange.value ? logDateRange.value[0] : undefined,
      endDate: logDateRange.value ? logDateRange.value[1] : undefined,
    }
    const data = await systemApi.getNotificationLogs(params)
    logs.value = data.list || []
    logTotal.value = data.total || 0
  } catch {
    logs.value = []
  } finally {
    logLoading.value = false
  }
}

const onResend = async (row) => {
  resendingId.value = row.id
  try {
    const data = await systemApi.resendNotification(row.id)
    ElMessage.success(data.message || '已重发')
    await loadLogs()
  } catch {
    // 拦截器已提示
  } finally {
    resendingId.value = null
  }
}

const onTrigger = async () => {
  triggering.value = true
  try {
    const data = await systemApi.triggerNotification()
    ElMessage.success(data.message || '已触发扫描')
    await loadLogs()
  } catch {
    // 拦截器已提示
  } finally {
    triggering.value = false
  }
}

onMounted(() => {
  loadChannels()
  loadLibraryRules()
  loadLimits()
  loadPrompt()
  loadRules()
  loadMail()
})
</script>

<style scoped>
/* ===== 布局：左侧目次 + 右侧面板 ===== */
.settings-layout {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: var(--space-5);
  align-items: start;
}

.settings-nav {
  position: sticky;
  top: calc(var(--header-height) + 16px);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-3);
}

.nav-toc {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  padding: var(--space-1) var(--space-2) var(--space-2);
}

/* 分组标题：聚合同类目次项（如 AI 渠道 / AI 人设归到「AI 配置」组） */
.nav-group-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  margin-top: var(--space-3);
  padding: var(--space-3) var(--space-2) var(--space-1);
  border-top: 1px solid var(--color-divider);
}

/* 紧接总标题「设置目次」的第一个分组不画分隔线 */
.nav-toc + .nav-group-title {
  margin-top: 0;
  border-top: none;
}

.nav-group-title .en {
  font-size: 9px;
  font-family: var(--font-mono);
  letter-spacing: 0.06em;
  opacity: 0.7;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: 9px var(--space-2);
  border: none;
  background: transparent;
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: var(--fs-body);
  font-family: var(--font-sans);
  cursor: pointer;
  text-align: left;
  transition: background var(--duration-fast) var(--ease-soft), color var(--duration-fast) var(--ease-soft);
}

.nav-item:hover {
  background: var(--color-bg-subtle);
  color: var(--color-text);
}

.nav-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}

.nav-label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-en {
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.nav-item.active .nav-en {
  color: var(--color-primary);
  opacity: 0.7;
}

/* ===== 卡片通用 ===== */
.panel-card {
  padding: var(--space-6);
  margin-bottom: var(--space-5);
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
}

.card-head--between {
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.card-head-main {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
}

.card-head__icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--color-primary-light);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-head__icon.icon-coin {
  background: var(--color-secondary-light);
  color: var(--color-secondary);
}

.card-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 4px;
}

.card-sub {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin: 0;
  line-height: 1.5;
}

.head-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* ===== AI 渠道 ===== */
.channel-hint {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 10px 14px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.channel-hint .app-icon,
.channel-hint svg {
  color: var(--color-primary);
  flex-shrink: 0;
  margin-top: 3px;
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-4);
  min-height: 60px;
}

.channel-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 14px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-elevated);
  transition: border-color var(--duration-fast) var(--ease-soft),
    box-shadow var(--duration-fast) var(--ease-soft),
    opacity var(--duration-fast) var(--ease-soft);
}

.channel-card:hover {
  border-color: var(--color-border-strong);
  box-shadow: var(--shadow-sm);
}

.channel-card.is-disabled {
  opacity: 0.58;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: var(--radius-pill);
  flex-shrink: 0;
}

.status-dot.dot-active {
  background: var(--color-success);
  box-shadow: 0 0 0 3px rgba(93, 138, 107, 0.16);
  animation: dot-pulse 2.4s var(--ease-soft) infinite;
}

.status-dot.dot-standby {
  border: 2px solid var(--color-success);
  background: transparent;
}

.status-dot.dot-off {
  background: var(--color-border-strong);
}

@keyframes dot-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(93, 138, 107, 0.16); }
  50% { box-shadow: 0 0 0 5px rgba(93, 138, 107, 0.06); }
}

.channel-icon {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.channel-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.channel-name-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.channel-name {
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.badge {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1;
  padding: 4px 9px;
  border-radius: var(--radius-pill);
  font-weight: 500;
}

.badge-active {
  background: var(--color-secondary-light);
  color: var(--color-success);
}

.badge-standby {
  background: var(--color-bg-subtle);
  color: var(--color-text-secondary);
}

.badge-off {
  background: var(--color-bg-muted);
  color: var(--color-text-tertiary);
}

.channel-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.provider-tag {
  font-size: 11px;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.model-tag {
  max-width: 220px;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-light);
  color: var(--color-primary);
  display: inline-flex;
  align-items: center;
}

.model-tag-text {
  font-family: var(--font-mono);
  max-width: 200px;
}

.key-tag {
  font-size: 11px;
  white-space: nowrap;
}

.key-tag.is-ok {
  color: var(--color-success);
}

.key-tag.is-warn {
  color: var(--color-warning);
}

/* 深度思考开启徽标 */
.think-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  white-space: nowrap;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}

.channel-endpoint {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.channel-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
}

.order-btns {
  display: flex;
  flex-direction: column;
  margin-right: var(--space-2);
}

.channel-actions .el-button {
  color: var(--color-text-tertiary);
  padding: 6px;
  margin-left: 0;
}

.channel-actions .el-button:hover {
  color: var(--color-primary);
  background: var(--color-bg-subtle);
}

.channel-actions .el-button.danger-btn:hover {
  color: var(--color-danger);
  background: rgba(168, 90, 74, 0.08);
}

.channel-actions .el-button.is-disabled,
.channel-actions .el-button.is-disabled:hover {
  color: var(--color-border-strong);
  background: transparent;
}

.channel-empty {
  margin-top: var(--space-4);
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-lg);
  padding: var(--space-7) var(--space-5);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  text-align: center;
  color: var(--color-text-tertiary);
}

.channel-empty svg {
  color: var(--color-border-strong);
}

.channel-empty.is-error {
  border-color: color-mix(in srgb, var(--color-danger) 40%, var(--color-border));
}

.channel-empty.is-error svg {
  color: var(--color-danger);
}

.channel-empty.is-error .empty-title {
  color: var(--color-danger);
}

.empty-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  color: var(--color-text);
  margin: var(--space-2) 0 0;
}

.empty-desc {
  font-size: var(--fs-small);
}

.channel-empty .el-button {
  margin-top: var(--space-3);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* ===== 借阅规则 ===== */
.fine-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
  flex-wrap: wrap;
}

.fine-input {
  width: 160px;
}

.fine-unit {
  font-size: var(--fs-body);
  color: var(--color-text-secondary);
}

.rule-effect-hint {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  line-height: 1.7;
  margin: var(--space-3) 0 0;
}

.limit-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-2);
}

.limit-role {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-4) var(--space-5);
}

.limit-role-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-4);
  border-bottom: 1px dashed var(--color-divider);
}

.role-name {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
}

.role-tag {
  font-size: 11px;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  background: var(--color-bg-subtle);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

.limit-fields {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--space-4);
}

.limit-field label {
  display: block;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}

.limit-input-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}

.limit-input-wrap .el-input-number {
  width: 100%;
}

.limit-unit {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.limit-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-top: var(--space-5);
  flex-wrap: wrap;
}

.limit-footer .rule-effect-hint {
  margin: 0;
  flex: 1;
  min-width: 240px;
}

/* ===== AI 人设 ===== */
.prompt-notice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--color-secondary-light);
  border-radius: var(--radius-md);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  line-height: 1.8;
  margin-bottom: var(--space-4);
}

.prompt-notice svg {
  color: var(--color-secondary);
  flex-shrink: 0;
  margin-top: 4px;
}

.prompt-notice p {
  margin: 0;
}

.prompt-notice code {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  background: var(--color-bg-elevated);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  color: var(--color-primary);
}

.prompt-editor :deep(.el-textarea__inner) {
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  line-height: 1.9;
  border-color: var(--color-border-strong);
}

.prompt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-3);
  gap: var(--space-3);
  flex-wrap: wrap;
}

.prompt-count {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-family: var(--font-mono);
}

/* ===== 催还通知 · 规则 ===== */
.rule-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.rule-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-4) var(--space-5);
  transition: opacity var(--duration-fast) var(--ease-soft), border-color var(--duration-fast) var(--ease-soft);
}

.rule-card.is-off {
  opacity: 0.62;
}

.rule-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.rule-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  font-weight: 600;
  color: var(--color-text);
}

.rule-title .app-icon,
.rule-title svg {
  color: var(--color-primary);
}

.rule-head-right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.rule-toggle {
  color: var(--color-text-tertiary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-small);
}

.rule-toggle:hover {
  color: var(--color-primary);
  background: var(--color-bg-subtle);
}

.rule-fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-5);
  margin-top: var(--space-4);
}

.rule-field {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.rule-field label {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.rule-select {
  width: 150px;
}

.rule-unit {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.rule-tpl {
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px dashed var(--color-divider);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.tpl-field label {
  display: block;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}

.tpl-hint {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  line-height: 1.8;
  margin: 0;
}

.tpl-hint code {
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--color-bg-elevated);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  color: var(--color-primary);
}

/* 邮件渠道配置 */
.icon-mail {
  background: var(--color-secondary-light);
  color: var(--color-secondary);
}

.mail-form {
  margin-top: var(--space-4);
}

.mail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
}

.mail-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mail-field--wide {
  grid-column: 1 / -1;
}

.mail-field label {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.mail-switches {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-6);
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-divider);
}

.mail-switch-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.switch-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.switch-label {
  font-size: var(--fs-small);
  color: var(--color-text);
  font-weight: 500;
}

.switch-desc {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 通知管理 */
.log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.log-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  min-width: 0;
}

.log-filter {
  width: 120px;
}

.log-date {
  width: 210px;
}

.log-toolbar-right {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

.log-table {
  margin-top: var(--space-2);
}

.cell-event {
  font-weight: 500;
  color: var(--color-text);
}

.cell-email {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}

.log-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}

/* ===== 响应式 ===== */
@media (max-width: 1100px) {
  .limit-fields {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 900px) {
  .settings-layout {
    grid-template-columns: 1fr;
  }

  .settings-nav {
    position: static;
    display: flex;
    gap: var(--space-2);
    overflow-x: auto;
    padding: var(--space-2);
  }

  .nav-toc {
    display: none;
  }

  .nav-group-title {
    display: none;
  }

  .nav-item {
    width: auto;
    white-space: nowrap;
  }

  .nav-en {
    display: none;
  }

  .limit-fields {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .limit-fields {
    grid-template-columns: 1fr;
  }

  .panel-card {
    padding: var(--space-4);
  }
}
</style>
