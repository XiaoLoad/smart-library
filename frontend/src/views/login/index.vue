<template>
  <div class="login-page">
    <!-- 左侧 · 品牌视觉区 -->
    <section class="login-visual">
      <div class="visual-inner">
        <div class="visual-mark">
          <div class="mark-char">智</div>
          <div class="mark-sub">图书馆</div>
        </div>
        <h1 class="visual-headline">
          于书海之间<br />
          寻得一席静处
        </h1>
        <p class="visual-desc">
          一座智慧图书馆，连接读者与每一册值得相遇的书。<br />
          以数据洞察馆藏，以对话延展阅读。
        </p>
        <div class="visual-footer">
          <span class="footer-line"></span>
          <span class="footer-text">SMART · LIBRARY · SYSTEM · 2026</span>
        </div>
      </div>
      <!-- 装饰：竖排古文 -->
      <div class="vertical-text">
        书 · 卷 · 静 · 思 · 知
      </div>
    </section>

    <!-- 右侧 · 登录表单 -->
    <section class="login-form-section">
      <div class="form-wrapper">
        <div class="form-header">
          <span class="eyebrow">欢迎归来</span>
          <h2 class="form-title">登录至智图</h2>
          <p class="form-subtitle">请输入您的账号凭据以继续</p>
        </div>

        <form class="login-form" @submit.prevent="handleLogin">
          <div class="field">
            <label class="field-label">账号</label>
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              size="large"
              :prefix-icon="User"
              clearable
            />
          </div>

          <div class="field">
            <label class="field-label">密码</label>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              :prefix-icon="Lock"
              show-password
            />
          </div>

          <div class="form-row">
            <el-checkbox v-model="form.remember">保持登录</el-checkbox>
            <button type="button" class="forgot-link" @click="openDoc('forgot')">忘记密码？</button>
          </div>

          <button type="submit" class="submit-btn" :disabled="loading">
            <span v-if="!loading">登 录</span>
            <span v-else class="loading-text">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </span>
          </button>
        </form>

        <div class="form-footer">
          登录即表示您同意
          <button type="button" class="doc-link" @click="openDoc('terms')">服务条款</button>
          与
          <button type="button" class="doc-link" @click="openDoc('privacy')">隐私政策</button>
        </div>
      </div>
    </section>

    <el-dialog
      v-model="docVisible"
      :title="docTitle"
      width="560px"
      class="login-doc"
      append-to-body
    >
      <div class="doc-body" v-html="docHtml"></div>
      <template #footer>
        <el-button type="primary" @click="docVisible = false">我知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({
  username: '',
  password: '',
  remember: true,
})

const loading = ref(false)

/* 说明类文档（忘记密码 / 服务条款 / 隐私政策） */
const docs = {
  forgot: {
    title: '忘记密码',
    html: `
      <p>为保障账号安全，本系统不提供在线找回密码。若您遗忘登录密码，请按以下方式处理：</p>
      <ul>
        <li>携带本人有效证件（学生证、教师工作证或身份证）前往<strong>图书馆前台</strong>；</li>
        <li>向工作人员说明需重置密码，由工作人员核验身份后为您重置；</li>
        <li>重置后请使用初始密码登录，并及时在「个人中心」修改为便于记忆的新密码。</li>
      </ul>
      <p class="doc-tip">前台服务时间：周一至周五 8:30—17:30，节假日以馆内公告为准。</p>
      <p>如不便到馆，也可通过所在院系或部门联系图书馆管理员协助处理。</p>
    `,
  },
  terms: {
    title: '服务条款',
    html: `
      <p>欢迎使用智慧图书借阅管理系统（以下简称「本系统」）。您登录并使用本系统，即表示您已阅读并同意以下条款。</p>
      <h4>一、服务内容</h4>
      <p>本系统为图书馆读者与馆员提供馆藏查询、借阅管理、续借与归还、违约处理、阅读推荐等服务。具体功能以系统实际提供为准。</p>
      <h4>二、账号与安全</h4>
      <p>账号仅限本人使用，不得转借、出租或与他人共用。因保管不善导致的账号被盗用、信息泄露及由此产生的损失，由账号持有人承担。</p>
      <h4>三、借阅规则</h4>
      <p>借阅册数、借阅期限、续借次数及逾期违约金标准，以本馆现行借阅规则为准；逾期未还或图书损毁、遗失的，须按规定承担相应责任。</p>
      <h4>四、使用规范</h4>
      <p>您不得利用本系统从事任何违法违规活动，不得干扰系统正常运行、批量抓取数据或尝试未授权访问。违反者本馆有权暂停或终止其使用权限。</p>
      <h4>五、服务变更与终止</h4>
      <p>本馆可根据业务需要调整服务内容或规则，并通过系统公告等方式告知。因系统维护、升级或不可抗力导致服务中断的，本馆将尽力提前告知。</p>
      <h4>六、其他</h4>
      <p>本条款未尽事宜，依照国家法律法规及本馆相关规定执行。若条款发生变更，将在本页面更新，继续使用即视为接受变更后的条款。</p>
    `,
  },
  privacy: {
    title: '隐私政策',
    html: `
      <p>本馆重视您的个人信息保护。本政策说明本系统收集、使用与保护您个人信息的方式。</p>
      <h4>一、我们收集的信息</h4>
      <ul>
        <li>账号信息：用户名、密码（加密存储）、姓名、用户类型及所属院系或部门；</li>
        <li>业务信息：借阅、续借、归还记录，逾期与违约处理记录，检索与推荐偏好；</li>
        <li>日志信息：登录时间、操作记录及必要的设备与网络信息，用于安全审计。</li>
      </ul>
      <h4>二、信息的使用</h4>
      <p>上述信息仅用于身份核验、借阅服务办理、逾期提醒、服务改进与安全审计，不会用于与服务无关的商业用途。</p>
      <h4>三、信息的共享与披露</h4>
      <p>除法律法规要求、司法机关依法调取或经您另行同意外，本馆不会向第三方提供您的个人信息。</p>
      <h4>四、存储与保护</h4>
      <p>您的信息存储于本馆管理的系统服务器，密码采用不可逆加密方式保存。本馆采取访问控制、日志审计等措施防止信息被未授权访问、泄露或篡改。</p>
      <h4>五、您的权利</h4>
      <p>您可查询本人账号与借阅信息，并可申请更正错误信息。如需注销账号或删除个人信息，请前往图书馆前台办理。</p>
      <h4>六、未成年人保护</h4>
      <p>未成年读者使用本系统，应在监护人知情并同意的前提下进行。</p>
      <h4>七、联系我们</h4>
      <p>如对本政策有疑问，请联系图书馆前台工作人员。</p>
    `,
  },
}

const docVisible = ref(false)
const docKey = ref('')
const docTitle = computed(() => docs[docKey.value]?.title || '')
const docHtml = computed(() => docs[docKey.value]?.html || '')

const openDoc = (key) => {
  docKey.value = key
  docVisible.value = true
}

const handleLogin = async () => {
  if (loading.value) return
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    await userStore.login({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/dashboard')
  } catch (e) {
    // 错误提示已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: var(--color-bg);
}

/* 左侧视觉区 */
.login-visual {
  position: relative;
  background: linear-gradient(180deg, #2c2825 0%, #1a1815 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: var(--space-8);
}

.login-visual::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle at 30% 20%, rgba(58, 90, 107, 0.25) 0%, transparent 50%),
    radial-gradient(circle at 70% 80%, rgba(122, 146, 112, 0.15) 0%, transparent 50%);
  pointer-events: none;
}

.visual-inner {
  position: relative;
  z-index: 2;
  max-width: 480px;
  color: var(--color-text-inverse);
}

.visual-mark {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-7);
}

.mark-char {
  font-family: var(--font-serif);
  font-size: 4rem;
  font-weight: 600;
  color: var(--color-text-inverse);
  line-height: 1;
}

.mark-sub {
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  text-transform: uppercase;
  letter-spacing: var(--ls-wide);
  color: rgba(250, 248, 245, 0.5);
}

.visual-headline {
  font-family: var(--font-serif);
  font-size: 3rem;
  font-weight: 600;
  line-height: 1.25;
  letter-spacing: var(--ls-tight);
  margin-bottom: var(--space-5);
  color: var(--color-text-inverse);
}

.visual-desc {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: rgba(250, 248, 245, 0.6);
  margin-bottom: var(--space-8);
}

.visual-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.footer-line {
  flex: 1;
  height: 1px;
  background: rgba(250, 248, 245, 0.15);
}

.footer-text {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: rgba(250, 248, 245, 0.4);
}

.vertical-text {
  position: absolute;
  right: var(--space-6);
  top: 50%;
  transform: translateY(-50%);
  writing-mode: vertical-rl;
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  letter-spacing: var(--space-4);
  color: rgba(250, 248, 245, 0.12);
  font-weight: 500;
}

/* 右侧表单区 */
.login-form-section {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-8);
}

.form-wrapper {
  width: 100%;
  max-width: 400px;
}

.form-header {
  margin-bottom: var(--space-7);
}

.form-title {
  font-family: var(--font-serif);
  font-size: var(--fs-hero);
  font-weight: 600;
  margin: var(--space-2) 0;
  letter-spacing: var(--ls-tight);
}

.form-subtitle {
  color: var(--color-text-tertiary);
  font-size: var(--fs-small);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.field-label {
  font-size: var(--fs-caption);
  text-transform: uppercase;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  font-weight: 500;
}

.form-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.forgot-link {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  transition: color var(--duration-fast) var(--ease-soft);
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  font-family: inherit;
}

.forgot-link:hover {
  color: var(--color-primary);
}

.submit-btn {
  margin-top: var(--space-2);
  height: 48px;
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border: none;
  border-radius: var(--radius-md);
  font-family: var(--font-sans);
  font-size: var(--fs-body);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  cursor: pointer;
  transition: all var(--duration) var(--ease-soft);
}

.submit-btn:hover:not(:disabled) {
  background: var(--color-primary-hover);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.submit-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.loading-text {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-inverse);
  animation: pulse 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes pulse {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* 说明文档入口 */
.form-footer {
  margin-top: var(--space-7);
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.doc-link {
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  font-family: inherit;
  font-size: inherit;
  color: var(--color-primary);
}

.doc-link:hover {
  color: var(--color-primary-hover);
  text-decoration: underline;
}

/* 响应式 */
@media (max-width: 968px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .login-visual {
    display: none;
  }
  .login-form-section {
    min-height: 100vh;
  }
}

@media (max-width: 480px) {
  .form-wrapper { max-width: 100%; }
  .visual-headline { font-size: 2rem; }
}
</style>

<!-- 弹窗内容被 teleport 到 body，需用非 scoped 样式 -->
<style>
.login-doc .el-dialog__title {
  font-family: var(--font-serif);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
}

.login-doc .el-dialog__body {
  max-height: 60vh;
  overflow-y: auto;
}

.login-doc .doc-body {
  font-size: var(--fs-small);
  line-height: var(--lh-relaxed);
  color: var(--color-text-secondary);
}

.login-doc .doc-body p {
  margin: 0 0 var(--space-3);
}

.login-doc .doc-body h4 {
  margin: var(--space-4) 0 var(--space-2);
  font-size: var(--fs-body);
  color: var(--color-text);
  font-weight: 600;
}

.login-doc .doc-body ul {
  margin: 0 0 var(--space-3);
  padding-left: var(--space-5);
}

.login-doc .doc-body li {
  margin-bottom: var(--space-1);
}

.login-doc .doc-body .doc-tip {
  padding: var(--space-2) var(--space-3);
  background: var(--color-bg-subtle);
  border-radius: var(--radius-sm);
  color: var(--color-text-tertiary);
}

.login-doc .doc-body strong {
  color: var(--color-text);
}
</style>
