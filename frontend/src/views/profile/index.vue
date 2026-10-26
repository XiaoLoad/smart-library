<template>
  <div class="page profile-page">
    <header class="page-header">
      <span class="eyebrow">{{ isAdmin ? '账户 · PROFILE' : '读者 · PROFILE' }}</span>
      <h1>个人中心</h1>
      <p class="desc">{{ headerDesc }}</p>
    </header>

    <div v-if="profile" class="profile-layout">
      <!-- 左栏 · 不可改的身份事实 -->
      <aside class="profile-side">
        <section class="card identity-card fade-in">
          <div class="identity-head">
            <el-avatar :size="60" class="identity-avatar">{{ avatarText }}</el-avatar>
            <div class="identity-meta">
              <h2 class="identity-name">{{ profile.realName || '—' }}</h2>
              <div class="identity-account">@{{ profile.username || '—' }}</div>
              <span class="role-chip" :class="`role-${profile.userType}`">{{ roleText }}</span>
            </div>
          </div>

          <dl class="identity-list">
            <div v-for="row in identityRows" :key="row.key" class="identity-row">
              <dt>{{ row.label }}</dt>
              <dd :class="{ 'is-mono': row.mono }">{{ row.value }}</dd>
            </div>
            <div class="identity-row">
              <dt>加入时间</dt>
              <dd class="is-mono">{{ formatDate(profile.createTime) }}</dd>
            </div>
            <div class="identity-row">
              <dt>账户状态</dt>
              <dd>{{ profile.status === 1 ? '正常' : '禁用' }}</dd>
            </div>
          </dl>

          <p class="identity-note">以上信息由图书馆统一维护，如需变更请联系管理员。</p>
        </section>

        <!-- 借阅概览：管理员不参与流通，不展示 -->
        <section v-if="!isAdmin" class="card quota-card fade-in">
          <div class="quota-head">
            <span class="quota-title">借阅概览</span>
            <span class="quota-state" :class="{ 'is-blocked': quota && !quota.canBorrow }">
              {{ quotaStateText }}
            </span>
          </div>

          <div v-if="quota" class="quota-grid">
            <div class="quota-cell">
              <div class="num">{{ quota.maxBooks }}</div>
              <div class="lbl">可借上限</div>
            </div>
            <div class="quota-cell">
              <div class="num">{{ quota.used }}</div>
              <div class="lbl">在借中</div>
            </div>
            <div class="quota-cell">
              <div class="num">{{ quota.remaining }}</div>
              <div class="lbl">剩余可借</div>
            </div>
            <div class="quota-cell" :class="{ 'is-warn': quota.overdueCount > 0 }">
              <div class="num">{{ quota.overdueCount }}</div>
              <div class="lbl">逾期未还</div>
            </div>
          </div>

          <div v-if="quota && quota.maxBooks > 0" class="quota-bar-wrap">
            <div class="stock-bar">
              <div class="stock-bar__fill" :style="{ width: usedPercent + '%' }"></div>
            </div>
            <div class="quota-bar-hint">已用 {{ quota.used }} / {{ quota.maxBooks }} 册</div>
          </div>
        </section>
      </aside>

      <!-- 右栏 · 可维护的信息 -->
      <div class="profile-main">
        <section class="card panel-card fade-in">
          <div class="card-head">
            <div class="card-head__icon"><AppIcon name="pencil" :size="22" /></div>
            <div>
              <h2 class="card-title">联系与档案</h2>
              <p class="card-sub">{{ editHint }}</p>
            </div>
          </div>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-width="84px"
            class="profile-form"
            @submit.prevent
          >
            <el-form-item
              v-for="f in formFields"
              :key="f.key"
              :label="f.label"
              :prop="f.editable ? f.key : undefined"
            >
              <el-input
                v-model="form[f.key]"
                :placeholder="f.placeholder"
                :disabled="!f.editable"
                :clearable="!!f.editable"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
              <el-button @click="resetForm">重置</el-button>
            </el-form-item>
          </el-form>
        </section>

        <section class="card panel-card fade-in">
          <div class="card-head">
            <div class="card-head__icon icon-lock"><AppIcon name="lock" :size="22" /></div>
            <div>
              <h2 class="card-title">登录密码</h2>
              <p class="card-sub">修改后请使用新密码登录，建议定期更换。</p>
            </div>
          </div>

          <ChangePasswordForm />
        </section>

        <section class="card panel-card fade-in">
          <div class="card-head">
            <div class="card-head__icon"><AppIcon name="sparkles" :size="22" /></div>
            <div>
              <h2 class="card-title">AI 悬浮球</h2>
              <p class="card-sub">在任意页面右下角显示 AI 助手悬浮球，点击即可展开对话。</p>
            </div>
          </div>
          <div class="float-switch-row">
            <div>
              <div class="switch-label">启用 AI 悬浮球</div>
              <div class="switch-desc">关闭后所有页面不再显示悬浮球入口</div>
            </div>
            <el-switch v-model="aiFloatOn" @change="onAiFloatChange" />
          </div>
        </section>
      </div>
    </div>

    <div v-else-if="loaded" class="card empty-state fade-in">
      <p>档案加载失败，请稍后重试。</p>
      <el-button class="retry-btn" @click="load">重新加载</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { userApi, borrowApi } from '@/api'
import { formatDate } from '@/utils/datetime'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading'
import AppIcon from '@/components/AppIcon.vue'
import ChangePasswordForm from '@/components/ChangePasswordForm.vue'

/**
 * 个人中心。管理员与读者共用此页，可见字段按身份裁剪：
 * 左栏放不可改的身份事实，右栏放本人可维护的联系方式与档案字段。
 */

const userStore = useUserStore()

const isAdmin = computed(() => userStore.isAdmin)
const isStudent = computed(() => userStore.isStudent)

const profile = ref(null)
const quota = ref(null)
const loaded = ref(false)

const ROLE_TEXT = { 1: '教师', 2: '学生', 3: '管理员' }
const roleText = computed(() => ROLE_TEXT[profile.value?.userType] || '—')
const avatarText = computed(() => profile.value?.realName?.charAt(0) || 'U')
const headerDesc = computed(() =>
  isAdmin.value
    ? '查看本人账户信息，维护联系方式与登录密码。'
    : '查看本人档案与借阅额度，维护联系方式与登录密码。'
)
const editHint = computed(() =>
  isAdmin.value
    ? '姓名、工号、院系与职称由超级管理员维护，此处仅可修改邮箱与手机号。'
    : '姓名、学号/工号及院系、班级等档案信息由图书馆统一维护，此处仅可修改邮箱与手机号。'
)

// 表单字段：editable 为假者置灰展示（档案信息归图书馆维护，本人只维护联系方式）；
// roles 缺省表示所有身份可见，教师与学生各取自己的档案字段
const PROFILE_FIELDS = [
  { key: 'email', label: '邮箱', placeholder: 'example@mail.com', editable: true },
  { key: 'phone', label: '手机', placeholder: '11 位手机号', editable: true },
  { key: 'department', label: '院系', roles: [1, 2] },
  { key: 'title', label: '职称', roles: [1] },
  { key: 'className', label: '班级', roles: [2] },
  { key: 'grade', label: '年级', roles: [2] },
  { key: 'major', label: '专业', roles: [2] },
]

const formFields = computed(() =>
  PROFILE_FIELDS.filter((f) => !f.roles || f.roles.includes(userStore.userType))
)

// 左栏只放不出现在表单里的身份事实——表单中已置灰展示的档案信息不在此重复
const IDENTITY_FIELDS = [
  { key: 'teacherNo', label: '工号', mono: true },
  { key: 'studentNo', label: '学号', mono: true },
]

const identityRows = computed(() => {
  const shown = new Set(formFields.value.map((f) => f.key))
  return IDENTITY_FIELDS.filter((f) => !shown.has(f.key) && profile.value?.[f.key]).map((f) => ({
    ...f,
    value: profile.value[f.key],
  }))
})

const usedPercent = computed(() => {
  if (!quota.value?.maxBooks) return 0
  return Math.min(100, (quota.value.used / quota.value.maxBooks) * 100)
})

const quotaStateText = computed(() => {
  if (!quota.value) return ''
  if (quota.value.overdueCount > 0) return '有逾期未还'
  return quota.value.canBorrow ? '可正常借阅' : '已达借阅上限'
})

/* 表单 */
const formRef = ref(null)
const saving = ref(false)
const form = reactive({})

const buildFormState = (u) => {
  formFields.value.forEach((f) => {
    form[f.key] = u?.[f.key] ?? ''
  })
}

// 与后端 UserProfileUpdateRequest 的校验保持一致：两项皆必填
// 格式校验只在已填写时生效，留空交由 required 提示，避免同一格堆两条错误
const patternIfFilled = (pattern, message) => ({
  validator: (rule, value, callback) => {
    if (!value || pattern.test(value)) callback()
    else callback(new Error(message))
  },
  trigger: 'blur',
})

const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { max: 80, message: '邮箱长度不能超过 80 个字符', trigger: 'blur' },
    patternIfFilled(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, '邮箱格式不正确'),
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    patternIfFilled(/^1[3-9]\d{9}$/, '请输入 11 位手机号'),
  ],
}

const resetForm = () => {
  buildFormState(profile.value)
  formRef.value?.clearValidate()
}

const save = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = {}
      formFields.value
        .filter((f) => f.editable)
        .forEach((f) => {
          payload[f.key] = form[f.key]
        })
      const updated = await userApi.updateProfile(payload)
      profile.value = updated
      buildFormState(updated)
      ElMessage.success('保存成功')
    } catch {
      // 错误已由拦截器提示
    } finally {
      saving.value = false
    }
  })
}

/* 加载 */
const load = async () => {
  showPageLoading()
  try {
    const data = await userApi.profile()
    profile.value = data
    buildFormState(data)
    // 额度仅读者关心，管理员拿到的是空规则
    if (!userStore.isAdmin) {
      try {
        quota.value = await borrowApi.quota()
      } catch {
        // 概览拿不到不影响档案维护
      }
    }
  } catch {
    // 错误已由拦截器提示，交由空状态兜底
  } finally {
    loaded.value = true
    hidePageLoading()
  }
}

onMounted(load)

/* AI 悬浮球开关（与系统设置共用 localStorage 键与同步事件） */
const FLOAT_KEY = 'ai_float_disabled'
const aiFloatOn = ref(localStorage.getItem(FLOAT_KEY) !== '1')
const onAiFloatChange = (val) => {
  if (val) localStorage.removeItem(FLOAT_KEY)
  else localStorage.setItem(FLOAT_KEY, '1')
  window.dispatchEvent(new CustomEvent('ai-float-toggle', { detail: { enabled: !!val } }))
}
</script>

<style scoped>
.profile-layout {
  display: grid;
  grid-template-columns: 344px minmax(0, 1fr);
  gap: var(--space-5);
  align-items: start;
}

.profile-side,
.profile-main {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* 身份卡 */
.identity-card {
  padding: var(--space-5);
}

.identity-head {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
}

.identity-avatar {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  font-family: var(--font-serif);
  font-size: 1.5rem;
  font-weight: 600;
  flex-shrink: 0;
}

.identity-meta {
  min-width: 0;
}

.identity-name {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  line-height: 1.2;
  color: var(--color-text);
}

.identity-account {
  margin-top: 3px;
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  word-break: break-all;
}

.role-chip {
  display: inline-block;
  margin-top: var(--space-2);
  padding: 2px 9px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-caption);
  font-weight: 500;
}

.role-chip.role-1 {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.role-chip.role-2 {
  color: var(--color-secondary);
  background: var(--color-secondary-light);
}

.role-chip.role-3 {
  color: var(--color-warning);
  background: rgba(196, 144, 74, 0.12);
}

.identity-list {
  margin: var(--space-3) 0 0;
}

.identity-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 8px 0;
  border-bottom: 1px solid var(--color-divider);
}

.identity-row:last-child {
  border-bottom: none;
}

.identity-row dt {
  flex-shrink: 0;
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.identity-row dd {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--color-text);
  text-align: right;
  word-break: break-all;
}

.identity-row dd.is-mono {
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
}

.identity-note {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
  font-size: var(--fs-caption);
  line-height: var(--lh-normal);
  color: var(--color-text-tertiary);
}

/* 借阅概览 */
.quota-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.quota-title {
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.quota-state {
  font-size: var(--fs-caption);
  color: var(--color-success);
}

.quota-state.is-blocked {
  color: var(--color-danger);
}

.quota-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-3);
}

.quota-cell .num {
  font-family: var(--font-serif);
  font-size: 1.625rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-text);
}

.quota-cell.is-warn .num {
  color: var(--color-danger);
}

.quota-cell .lbl {
  margin-top: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.quota-bar-wrap {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
}

.quota-bar-hint {
  margin-top: var(--space-2);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 右侧卡片 */
.panel-card {
  padding: var(--space-6);
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
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

.card-head__icon.icon-lock {
  background: var(--color-secondary-light);
  color: var(--color-secondary);
}

.card-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 4px;
}

.card-sub {
  font-size: var(--fs-small);
  line-height: var(--lh-normal);
  color: var(--color-text-secondary);
}

.profile-form {
  max-width: 480px;
}

/* 档案字段置灰展示：底色沿用全局禁用令牌，文字抬到次文字色以保证可读 */
.profile-form :deep(.el-input.is-disabled .el-input__inner) {
  color: var(--color-text-secondary);
  -webkit-text-fill-color: var(--color-text-secondary);
}

.retry-btn {
  margin-top: var(--space-4);
}

@media (max-width: 980px) {
  .profile-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 480px) {
  .panel-card {
    padding: var(--space-5) var(--space-4);
  }
}

/* AI 悬浮球开关行 */
.float-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
}
.switch-label {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--color-text);
}
.switch-desc {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}
</style>
