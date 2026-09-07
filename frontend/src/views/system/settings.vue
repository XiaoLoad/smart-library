<template>
  <div class="page settings-page">
    <header class="page-header">
      <span class="eyebrow">管理 · SETTINGS</span>
      <h1>系统设置</h1>
      <p class="desc">管理员账户安全与网站 AI 接口配置。</p>
    </header>

    <div class="settings-grid">
      <!-- 密码修改卡片 -->
      <section class="card settings-card fade-in">
        <div class="card-head">
          <div class="card-head__icon">
            <el-icon><Lock /></el-icon>
          </div>
          <div>
            <h2 class="card-title">修改密码</h2>
            <p class="card-sub">为账户设置新的登录密码，建议定期更换以保障安全。</p>
          </div>
        </div>

        <el-form
          :model="pwdForm"
          :rules="pwdRules"
          ref="pwdFormRef"
          label-width="100px"
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
      </section>

      <!-- AI 接口配置卡片 -->
      <section class="card settings-card fade-in">
        <div class="card-head">
          <div class="card-head__icon">
            <el-icon><ChatDotSquare /></el-icon>
          </div>
          <div>
            <h2 class="card-title">AI 助手接口</h2>
            <p class="card-sub">配置网站 AI 助手所对接的服务商接口，支持主流 AI 服务商。</p>
          </div>
        </div>

        <el-form
          :model="aiForm"
          :rules="aiRules"
          ref="aiFormRef"
          label-width="100px"
          class="settings-form"
        >
          <el-form-item label="服务商" prop="provider">
            <el-select v-model="aiForm.provider" placeholder="请选择服务商" style="width: 100%" @change="onProviderChange">
              <el-option
                v-for="p in providers"
                :key="p.value"
                :label="p.label"
                :value="p.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="模型" prop="model">
            <el-select v-model="aiForm.model" placeholder="请选择模型" style="width: 100%" :disabled="!aiForm.provider" filterable allow-create>
              <el-option
                v-for="m in currentModels"
                :key="m.value"
                :label="m.label"
                :value="m.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="API Key" prop="apiKey">
            <el-input
              v-model="aiForm.apiKey"
              type="password"
              show-password
              :placeholder="aiForm.apiKeySet ? '已配置，如需更换请输入新 Key' : '请输入 API Key'"
            />
            <div v-if="aiForm.apiKeySet && !aiForm.apiKey" class="field-hint">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>当前已配置 API Key（出于安全考虑不回显原文）</span>
            </div>
          </el-form-item>
          <el-form-item label="接口地址" prop="endpoint">
            <el-input v-model="aiForm.endpoint" placeholder="如 https://api.deepseek.com/v1/chat/completions" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="aiLoading" @click="submitAiConfig">保存配置</el-button>
            <el-button @click="loadAiConfig">重新加载</el-button>
          </el-form-item>
        </el-form>

        <div class="provider-tip" v-if="currentProviderInfo">
          <el-icon><InfoFilled /></el-icon>
          <div>
            <strong>{{ currentProviderInfo.label }} 接入说明：</strong>
            <span>{{ currentProviderInfo.tip }}</span>
            <a v-if="currentProviderInfo.url" :href="currentProviderInfo.url" target="_blank" class="tip-link">获取 API Key →</a>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Lock, ChatDotSquare, CircleCheckFilled, InfoFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api'

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

/* ===== AI 接口配置 ===== */
const aiFormRef = ref(null)
const aiLoading = ref(false)

// 市面主流 AI 服务商预设
const providers = [
  {
    value: 'deepseek',
    label: 'DeepSeek 深度求索',
    defaultEndpoint: 'https://api.deepseek.com/v1/chat/completions',
    defaultModel: 'deepseek-chat',
    models: [
      { value: 'deepseek-chat', label: 'DeepSeek Chat（通用对话）' },
      { value: 'deepseek-reasoner', label: 'DeepSeek Reasoner（推理模型）' },
    ],
    tip: '前往 DeepSeek 开放平台创建 API Key，新用户有免费额度。',
    url: 'https://platform.deepseek.com/api_keys',
  },
  {
    value: 'openai',
    label: 'OpenAI',
    defaultEndpoint: 'https://api.openai.com/v1/chat/completions',
    defaultModel: 'gpt-4o-mini',
    models: [
      { value: 'gpt-4o', label: 'GPT-4o' },
      { value: 'gpt-4o-mini', label: 'GPT-4o mini' },
      { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
      { value: 'gpt-3.5-turbo', label: 'GPT-3.5 Turbo' },
    ],
    tip: '需 OpenAI 账号并创建 API Key，国内访问需配置代理。',
    url: 'https://platform.openai.com/api-keys',
  },
  {
    value: 'qwen',
    label: '通义千问（阿里云）',
    defaultEndpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    defaultModel: 'qwen-plus',
    models: [
      { value: 'qwen-plus', label: 'Qwen Plus' },
      { value: 'qwen-turbo', label: 'Qwen Turbo' },
      { value: 'qwen-max', label: 'Qwen Max' },
      { value: 'qwen-long', label: 'Qwen Long（长文本）' },
    ],
    tip: '在阿里云 DashScope 平台申请 API Key，新用户有免费额度。',
    url: 'https://dashscope.console.aliyun.com/apiKey',
  },
  {
    value: 'moonshot',
    label: 'Moonshot 月之暗面（Kimi）',
    defaultEndpoint: 'https://api.moonshot.cn/v1/chat/completions',
    defaultModel: 'moonshot-v1-8k',
    models: [
      { value: 'moonshot-v1-8k', label: 'Moonshot v1 8K' },
      { value: 'moonshot-v1-32k', label: 'Moonshot v1 32K' },
      { value: 'moonshot-v1-128k', label: 'Moonshot v1 128K' },
    ],
    tip: '前往 Moonshot 平台创建 API Key，支持长上下文对话。',
    url: 'https://platform.moonshot.cn/console/api-keys',
  },
  {
    value: 'zhipu',
    label: '智谱 AI（ChatGLM）',
    defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    defaultModel: 'glm-4-flash',
    models: [
      { value: 'glm-4', label: 'GLM-4' },
      { value: 'glm-4-flash', label: 'GLM-4 Flash（免费）' },
      { value: 'glm-4-air', label: 'GLM-4 Air' },
      { value: 'glm-4-airx', label: 'GLM-4 AirX' },
    ],
    tip: '在智谱开放平台创建 API Key，GLM-4-Flash 模型完全免费。',
    url: 'https://open.bigmodel.cn/usercenter/apikeys',
  },
  {
    value: 'baichuan',
    label: '百川智能',
    defaultEndpoint: 'https://api.baichuan-ai.com/v1/chat/completions',
    defaultModel: 'Baichuan4-Turbo',
    models: [
      { value: 'Baichuan4-Turbo', label: 'Baichuan4 Turbo' },
      { value: 'Baichuan4-Air', label: 'Baichuan4 Air' },
      { value: 'Baichuan2-Turbo', label: 'Baichuan2 Turbo' },
    ],
    tip: '在百川开放平台创建 API Key，支持中文场景优化。',
    url: 'https://platform.baichuan-ai.com/console/apikey',
  },
  {
    value: 'minimax',
    label: 'MiniMax',
    defaultEndpoint: 'https://api.minimax.chat/v1/text/chatcompletion_v2',
    defaultModel: 'abab6.5s-chat',
    models: [
      { value: 'abab6.5s-chat', label: 'ABAB 6.5s Chat' },
      { value: 'abab6.5-chat', label: 'ABAB 6.5 Chat' },
      { value: 'abab6-chat', label: 'ABAB 6 Chat' },
    ],
    tip: '在 MiniMax 开放平台创建 API Key，支持超长文本。',
    url: 'https://platform.minimaxi.com/user-center/basic-information/interface-key',
  },
  {
    value: 'custom',
    label: '自定义接口（OpenAI 兼容）',
    defaultEndpoint: '',
    defaultModel: '',
    models: [],
    tip: '填写自定义的接口地址和模型名，需兼容 OpenAI Chat Completions 协议。',
    url: '',
  },
]

const aiForm = reactive({
  provider: 'deepseek',
  model: 'deepseek-chat',
  apiKey: '',
  apiKeySet: false,
  endpoint: 'https://api.deepseek.com/v1/chat/completions',
})

const aiRules = {
  provider: [{ required: true, message: '请选择服务商', trigger: 'change' }],
  model: [{ required: true, message: '请选择或输入模型名', trigger: 'blur' }],
}

const currentProviderInfo = computed(() => providers.find((p) => p.value === aiForm.provider))
const currentModels = computed(() => currentProviderInfo.value?.models || [])

const onProviderChange = () => {
  const info = currentProviderInfo.value
  if (!info) return
  aiForm.model = info.defaultModel
  aiForm.endpoint = info.defaultEndpoint
}

const loadAiConfig = async () => {
  try {
    const data = await systemApi.getAiConfig()
    aiForm.provider = data.provider || 'deepseek'
    aiForm.model = data.model || 'deepseek-chat'
    aiForm.endpoint = data.endpoint || ''
    aiForm.apiKey = data.apiKey || ''
    aiForm.apiKeySet = !!data.apiKeySet
  } catch {
    // 静默
  }
}

const submitAiConfig = () => {
  aiFormRef.value.validate(async (valid) => {
    if (!valid) return
    aiLoading.value = true
    try {
      const payload = {
        provider: aiForm.provider,
        model: aiForm.model,
        endpoint: aiForm.endpoint,
      }
      // 若用户修改了 apiKey 则提交，否则不提交空值以保留原值
      if (aiForm.apiKey) {
        payload.apiKey = aiForm.apiKey
      } else if (!aiForm.apiKeySet) {
        payload.apiKey = ''
      }
      const res = await systemApi.saveAiConfig(payload)
      ElMessage.success(res.message || 'AI 配置保存成功')
      await loadAiConfig()
    } catch {
      // 错误已由拦截器提示
    } finally {
      aiLoading.value = false
    }
  })
}

onMounted(() => {
  loadAiConfig()
})
</script>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(480px, 1fr));
  gap: var(--space-5);
  align-items: start;
}

.settings-card {
  padding: var(--space-6);
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
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
  font-size: 22px;
  flex-shrink: 0;
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

.settings-form {
  max-width: 460px;
}

.field-hint {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-top: var(--space-1);
  font-size: var(--fs-caption);
  color: var(--color-success, #67c23a);
}

.provider-tip {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.provider-tip .el-icon {
  color: var(--color-primary);
  flex-shrink: 0;
  margin-top: 2px;
}

.tip-link {
  display: inline-block;
  margin-left: var(--space-2);
  color: var(--color-primary);
  text-decoration: none;
  font-weight: 500;
}

.tip-link:hover {
  text-decoration: underline;
}

@media (max-width: 768px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}
</style>
