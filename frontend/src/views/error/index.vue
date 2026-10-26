<template>
  <div class="error-page">
    <div class="error-inner">
      <p class="error-eyebrow">
        <span class="eyebrow-tick" aria-hidden="true"></span>{{ copy.eyebrow }}
      </p>

      <h1 class="error-title">{{ copy.title }}</h1>
      <p class="error-desc">{{ description }}</p>

      <!-- 索书单：把用户请求的地址当作"索书号"回显，一眼看出错在哪 -->
      <div class="call-slip">
        <div class="slip-head">
          <span class="slip-label">{{ copy.slipLabel }} · CALL SLIP</span>
          <span class="slip-rule" aria-hidden="true"></span>
        </div>
        <div class="slip-body">
          <span class="slip-field">{{ copy.fieldLabel }}</span>
          <code class="slip-value">{{ target }}</code>
        </div>
        <span class="slip-seal" aria-hidden="true">{{ copy.seal }}</span>
      </div>

      <div class="error-actions">
        <button type="button" class="btn-primary" @click="goHome">回到概览</button>
        <button type="button" class="btn-text" @click="goBack">返回上一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/* 两种拦截共用这一页，只换文案与印章：路由通过 meta.errorCode 声明身份 */
const COPY = {
  404: {
    eyebrow: 'ERR 404 · 索引未命中',
    title: '这个位置没有书',
    desc: '你访问的地址不在馆藏索引里。可能是链接有误，也可能是这一册已从架上撤下。',
    slipLabel: '索书单',
    fieldLabel: '请求地址',
    seal: '查无此书',
  },
  403: {
    eyebrow: 'ERR 403 · 权限不足',
    title: '这一册在闭架区',
    desc: '闭架区的页面需要管理员权限，你的账号调阅不了。',
    slipLabel: '调阅申请',
    fieldLabel: '被拒页面',
    seal: '无权调阅',
  },
}

const code = computed(() => (route.meta?.errorCode === 403 ? 403 : 404))
const copy = computed(() => COPY[code.value])

/* 403 由守卫带上来源路径，据此把提示落到具体那一页 */
const deniedFrom = computed(() => {
  const from = route.query.from
  const raw = Array.isArray(from) ? from[0] : from
  return raw ? String(raw) : ''
})

/** 把被拒路径解析成页面名，让「闭架」有明确的所指 */
const deniedTitle = computed(() => {
  if (!deniedFrom.value) return null
  try {
    const matched = [...router.resolve(deniedFrom.value).matched].reverse()
    return matched.find((r) => r.meta?.title)?.meta?.title || null
  } catch {
    return null
  }
})

const description = computed(() => {
  if (code.value !== 403) return copy.value.desc
  const role = userStore.user?.roleName || '当前身份'
  const page = deniedTitle.value ? `《${deniedTitle.value}》` : '该页面'
  return `${page}仅对管理员开放，你当前的「${role}」调阅不了。`
})

const target = computed(() =>
  code.value === 403 ? deniedFrom.value || route.path : route.path
)

const goHome = () => router.push('/dashboard')
const goBack = () => router.back()
</script>

<style scoped>
.error-page {
  /* 页面上唯一一抹朱色：眉题刻线与印章共用，暗色下统一抬亮以免对比不足 */
  --error-accent: var(--color-danger);
  min-height: calc(100vh - var(--header-height));
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-8) var(--space-6);
}

[data-theme='dark'] .error-page {
  --error-accent: #c98a76;
}

.error-inner {
  width: 100%;
  max-width: 560px;
}

/* 眉题 · 旧朱红短刻线是页面上唯一一抹朱色 */
.error-eyebrow {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}

.eyebrow-tick {
  width: 14px;
  height: 2px;
  border-radius: 1px;
  background: var(--error-accent);
  flex-shrink: 0;
}

.error-title {
  font-family: var(--font-serif);
  font-size: var(--fs-hero);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  line-height: var(--lh-tight);
  color: var(--color-text);
  margin-bottom: var(--space-3);
}

.error-desc {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-6);
}

/* 索书单 */
.call-slip {
  position: relative;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-5);
  box-shadow: var(--shadow-sm);
}

.slip-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding-bottom: var(--space-3);
  border-bottom: 1px dashed var(--color-border-strong);
}

.slip-label {
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
  white-space: nowrap;
}

.slip-rule {
  flex: 1;
  height: 1px;
  background: var(--color-border);
}

.slip-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding-top: var(--space-4);
}

.slip-field {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

/* 请求地址按索书号处理：等宽、可换行、不裁切 */
.slip-value {
  font-family: var(--font-mono);
  font-size: var(--fs-body);
  line-height: var(--lh-snug);
  color: var(--color-text);
  word-break: break-all;
}

/* 印章 · 页面唯一的重音，落纸时盖下 */
.slip-seal {
  position: absolute;
  top: calc(var(--space-4) * -1);
  right: var(--space-4);
  padding: 5px 8px;
  border: 1.5px solid var(--error-accent);
  color: var(--error-accent);
  border-radius: var(--radius-sm);
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.22em;
  text-indent: 0.22em;
  white-space: nowrap;
  pointer-events: none;
  background: var(--color-bg-elevated);
  animation: seal-stamp var(--duration-slow) var(--ease-out) 160ms both;
}

@keyframes seal-stamp {
  from { opacity: 0; transform: rotate(-16deg) scale(1.4); }
  to { opacity: 0.9; transform: rotate(-8deg) scale(1); }
}

/* 操作 */
.error-actions {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin-top: var(--space-6);
}

.btn-primary {
  height: 40px;
  padding: 0 var(--space-6);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  font-family: var(--font-sans);
  font-size: var(--fs-body);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-soft),
    transform var(--duration-fast) var(--ease-soft);
}

.btn-primary:hover {
  background: var(--color-primary-hover);
  transform: translateY(-1px);
}

.btn-text {
  border: none;
  background: none;
  padding: 0;
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-soft);
}

.btn-text:hover {
  color: var(--color-primary);
}

.btn-primary:focus-visible,
.btn-text:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
  border-radius: var(--radius-sm);
}

@media (max-width: 480px) {
  .error-page {
    align-items: flex-start;
    padding: var(--space-6) var(--space-4);
  }
  .error-title {
    font-size: var(--fs-h1);
  }
  .error-actions {
    gap: var(--space-4);
  }
  .btn-primary {
    flex: 1;
  }
}
</style>
