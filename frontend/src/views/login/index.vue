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
              @keyup.enter="handleLogin"
            />
          </div>

          <div class="form-row">
            <el-checkbox v-model="form.remember">保持登录</el-checkbox>
            <a class="forgot-link" href="#">忘记密码？</a>
          </div>

          <button type="submit" class="submit-btn" :disabled="loading" @click="handleLogin">
            <span v-if="!loading">登 录</span>
            <span v-else class="loading-text">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </span>
          </button>
        </form>

        <!-- 演示账号快捷入口 -->
        <div class="demo-accounts">
          <div class="demo-title">演示账号 · 一键填充</div>
          <div class="demo-list">
            <button
              v-for="acc in demoAccounts"
              :key="acc.username"
              class="demo-chip"
              @click="fillDemo(acc)"
            >
              <span class="chip-label">{{ acc.label }}</span>
              <span class="chip-user">{{ acc.username }}</span>
            </button>
          </div>
        </div>

        <div class="form-footer">
          登录即表示您同意 <a href="#">服务条款</a> 与 <a href="#">隐私政策</a>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({
  username: 'admin',
  password: 'admin123',
  remember: true,
})

const loading = ref(false)

const demoAccounts = [
  { label: '管理员', username: 'admin', password: 'admin123' },
  { label: '教师', username: 'teacher01', password: '123456' },
  { label: '学生', username: 'student01', password: '123456' },
]

const fillDemo = (acc) => {
  form.username = acc.username
  form.password = acc.password
}

const handleLogin = async () => {
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
    // 错误已由拦截器提示
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

/* —— 左侧视觉区 —— */
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

/* —— 右侧表单区 —— */
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

/* —— 演示账号 —— */
.demo-accounts {
  margin-top: var(--space-6);
  padding-top: var(--space-5);
  border-top: 1px dashed var(--color-border-strong);
}

.demo-title {
  font-size: var(--fs-caption);
  text-transform: uppercase;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-3);
  font-weight: 500;
}

.demo-list {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.demo-chip {
  flex: 1;
  min-width: 80px;
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: left;
}

.demo-chip:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
}

.chip-label {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--color-text);
}

.chip-user {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.form-footer {
  margin-top: var(--space-6);
  text-align: center;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.form-footer a {
  color: var(--color-primary);
}

/* —— 响应式 —— */
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
