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
        <button
          v-for="s in sections"
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

            <el-form
              :model="pwdForm"
              :rules="pwdRules"
              ref="pwdFormRef"
              label-width="92px"
              class="settings-form"
            >
              <el-form-item label="原密码" prop="oldPassword">
                <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="pwdLoading" @click="submitPassword">确认修改</el-button>
                <el-button @click="resetPwdForm">重置</el-button>
              </el-form-item>
            </el-form>
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
      </div>
    </div>

    <AiChannelDrawer v-model="drawerVisible" :channel="editingChannel" @saved="loadChannels" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/components/AppIcon.vue'
import EllipsisText from '@/components/EllipsisText.vue'
import AiChannelDrawer from '@/components/AiChannelDrawer.vue'
import { systemApi } from '@/api'

/* ===== 目次导航 ===== */
const sections = [
  { key: 'account', label: '账户安全', en: 'ACCOUNT', icon: 'lock' },
  { key: 'ai', label: 'AI 渠道', en: 'CHANNELS', icon: 'cpu' },
  { key: 'rules', label: '借阅规则', en: 'RULES', icon: 'ruler' },
  { key: 'prompt', label: 'AI 人设', en: 'PERSONA', icon: 'sparkles' },
]
const active = ref('account')

// 切换目次：AI 渠道面板上次加载失败时自动重试（后端可能刚重启完成）
const onNav = (key) => {
  active.value = key
  if (key === 'ai' && channelsError.value) {
    loadChannels()
  }
}

/* ===== 密码修改 ===== */
const pwdFormRef = ref(null)
const pwdLoading = ref(false)
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

const submitPassword = () => {
  pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    pwdLoading.value = true
    try {
      const res = await systemApi.changePassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword,
      })
      ElMessage.success(res.message || '密码修改成功')
      resetPwdForm()
    } catch {
      // 错误已由拦截器提示
    } finally {
      pwdLoading.value = false
    }
  })
}

const resetPwdForm = () => {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdFormRef.value?.clearValidate()
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

onMounted(() => {
  loadChannels()
  loadLibraryRules()
  loadLimits()
  loadPrompt()
})
</script>

<style scoped>
/* ===== 布局：左侧目次 + 右侧面板 ===== */
.settings-layout {
  display: grid;
  grid-template-columns: 188px minmax(0, 1fr);
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

.settings-form {
  max-width: 460px;
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
