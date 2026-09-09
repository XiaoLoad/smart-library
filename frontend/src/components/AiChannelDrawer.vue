<template>
  <el-drawer
    v-model="visible"
    :size="480"
    direction="rtl"
    :close-on-click-modal="false"
    class="ai-channel-drawer"
  >
    <template #header>
      <div class="acd-head">
        <span class="eyebrow">{{ isEdit ? 'AI 渠道 · EDIT CHANNEL' : 'AI 渠道 · NEW CHANNEL' }}</span>
        <h2 class="acd-title">{{ isEdit ? '编辑渠道' : '新增渠道' }}</h2>
        <p class="acd-sub">
          {{ isEdit ? '修改保存后即时生效；API Key 留空表示不修改。' : '渠道按列表顺序启用第一个可用项，可随时调整优先级。' }}
        </p>
      </div>
    </template>

    <div class="acd-body">
      <el-form :model="form" :rules="rules" ref="formRef" label-position="top">
        <el-form-item label="渠道名称" prop="name">
          <el-input v-model="form.name" placeholder="便于辨认，如「DeepSeek 主渠道」" maxlength="30" />
        </el-form-item>

        <el-form-item label="服务商">
          <div class="preset-chips">
            <button
              v-for="p in PRESETS"
              :key="p.value"
              type="button"
              class="chip"
              :class="{ active: form.provider === p.value }"
              @click="applyPreset(p)"
            >{{ p.label }}</button>
          </div>
          <p class="field-note">选择服务商仅用于自动填写接口地址；模型列表始终从服务商实时拉取。</p>
        </el-form-item>

        <el-form-item label="接口地址" prop="endpoint">
          <el-input v-model="form.endpoint" placeholder="https://api.example.com/v1/chat/completions" class="mono-input" />
        </el-form-item>

        <el-form-item label="API Key" :prop="isEdit ? undefined : 'apiKey'">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="isEdit && form.apiKeySet ? '已配置，留空表示不修改' : '请输入 API Key'"
          />
          <p v-if="isEdit && form.apiKeySet && !form.apiKey" class="field-note is-ok">
            当前已配置 API Key，出于安全考虑不回显原文。
          </p>
        </el-form-item>

        <el-form-item label="模型" prop="model">
          <div class="model-row">
            <el-select
              v-model="form.model"
              placeholder="点击右侧按钮从服务商拉取，或手动输入模型名"
              filterable
              allow-create
              default-first-option
              style="flex: 1"
              :no-data-text="fetchedModels === null ? '请先拉取模型列表' : '无匹配模型，可直接输入'"
            >
              <el-option
                v-for="m in fetchedModels || []"
                :key="m.id"
                :label="m.name || m.id"
                :value="m.id"
              />
            </el-select>
            <el-button :loading="testing" @click="fetchModels">
              <AppIcon v-if="!testing" name="bolt" :size="15" />
              拉取模型
            </el-button>
          </div>
          <p v-if="fetchedModels" class="field-note is-ok">
            已从服务商实时拉取 {{ fetchedModels.length }} 个可用模型，可下拉选择；也可手动输入。
          </p>
        </el-form-item>

        <el-form-item>
          <div class="test-result" v-if="testResult" :class="testResult.ok ? 'ok' : 'fail'">
            <AppIcon :name="testResult.ok ? 'check' : 'bolt'" :size="15" />
            <span>{{ testResult.text }}</span>
          </div>
        </el-form-item>

        <el-form-item>
          <div class="enable-row">
            <div>
              <div class="enable-label">启用该渠道</div>
              <div class="enable-note">停用后系统将跳过此渠道，使用下一优先级渠道。</div>
            </div>
            <el-switch v-model="form.enabled" />
          </div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <div class="acd-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ isEdit ? '保存修改' : '添加渠道' }}
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AppIcon from './AppIcon.vue'
import { systemApi } from '@/api'

/**
 * AI 渠道新增/编辑抽屉。
 * - 模型下拉不内置任何静态列表：一律通过「拉取模型」从服务商 API 实时获取，
 *   拉取失败或未拉取时可手动输入模型名（allow-create）。
 * - 服务商预设仅用于快速填写接口地址。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 编辑时传入渠道对象；新增时传 null */
  channel: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const isEdit = computed(() => !!props.channel?.id)

// 服务商预设：仅用于预填接口地址（不提供模型列表）
const PRESETS = [
  { value: 'deepseek', label: 'DeepSeek', endpoint: 'https://api.deepseek.com/v1/chat/completions' },
  { value: 'qwen', label: '通义千问', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' },
  { value: 'moonshot', label: 'Kimi', endpoint: 'https://api.moonshot.cn/v1/chat/completions' },
  { value: 'zhipu', label: '智谱', endpoint: 'https://open.bigmodel.cn/api/paas/v4/chat/completions' },
  { value: 'openai', label: 'OpenAI', endpoint: 'https://api.openai.com/v1/chat/completions' },
  { value: 'baichuan', label: '百川', endpoint: 'https://api.baichuan-ai.com/v1/chat/completions' },
  { value: 'minimax', label: 'MiniMax', endpoint: 'https://api.minimax.chat/v1/text/chatcompletion_v2' },
  { value: 'custom', label: '自定义', endpoint: '' },
]

const formRef = ref(null)
const saving = ref(false)
const testing = ref(false)
const fetchedModels = ref(null)
const testResult = ref(null)

const form = reactive({
  name: '',
  provider: 'deepseek',
  endpoint: '',
  apiKey: '',
  apiKeySet: false,
  model: '',
  enabled: true,
})

const rules = {
  name: [{ required: true, message: '请输入渠道名称', trigger: 'blur' }],
  endpoint: [{ required: true, message: '请输入接口地址', trigger: 'blur' }],
  model: [{ required: true, message: '请拉取选择或手动输入模型名', trigger: 'change' }],
  apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
}

const resetForm = () => {
  form.name = ''
  form.provider = 'deepseek'
  form.endpoint = PRESETS[0].endpoint
  form.apiKey = ''
  form.apiKeySet = false
  form.model = ''
  form.enabled = true
  fetchedModels.value = null
  testResult.value = null
  formRef.value?.clearValidate()
}

watch(
  () => visible.value,
  (open) => {
    if (!open) return
    testResult.value = null
    fetchedModels.value = null
    if (props.channel) {
      form.name = props.channel.name || ''
      form.provider = props.channel.provider || 'custom'
      form.endpoint = props.channel.endpoint || ''
      form.apiKey = ''
      form.apiKeySet = !!props.channel.apiKeySet
      form.model = props.channel.model || ''
      form.enabled = props.channel.enabled !== false
    } else {
      resetForm()
    }
  },
)

const applyPreset = (p) => {
  form.provider = p.value
  if (p.endpoint) {
    form.endpoint = p.endpoint
  }
  // 切换服务商后已拉取模型失效，需重新拉取
  fetchedModels.value = null
  testResult.value = null
}

/**
 * 拉取模型 = 测试连通性。
 * 编辑模式且未输入新 Key 时，传 id 由后端使用已存储的 Key 测试。
 */
const fetchModels = async () => {
  if (!form.endpoint) {
    ElMessage.warning('请先填写接口地址')
    return
  }
  if (!form.apiKey && !(isEdit.value && form.apiKeySet)) {
    ElMessage.warning('请先输入 API Key')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const payload = { provider: form.provider, endpoint: form.endpoint }
    if (form.apiKey) {
      payload.apiKey = form.apiKey
    } else if (isEdit.value) {
      payload.id = props.channel.id
    }
    const data = await systemApi.testChannel(payload)
    fetchedModels.value = data.list || []
    testResult.value = { ok: true, text: `连接正常，拉取到 ${data.total} 个可用模型` }
    ElMessage.success(`已拉取 ${data.total} 个模型`)
  } catch {
    fetchedModels.value = null
    testResult.value = { ok: false, text: '连接失败：请检查接口地址与 API Key（错误详情见提示）' }
  } finally {
    testing.value = false
  }
}

const submit = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = {
        name: form.name,
        provider: form.provider,
        endpoint: form.endpoint,
        model: form.model,
        enabled: form.enabled,
      }
      if (form.apiKey) {
        payload.apiKey = form.apiKey
      }
      if (isEdit.value) {
        payload.id = props.channel.id
        await systemApi.updateChannel(payload)
        ElMessage.success('渠道已更新')
      } else {
        await systemApi.addChannel(payload)
        ElMessage.success('渠道已添加')
      }
      emit('saved')
      visible.value = false
    } catch {
      // 错误已由拦截器提示
    } finally {
      saving.value = false
    }
  })
}
</script>

<style scoped>
.acd-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.acd-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  margin: 0;
}

.acd-sub {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin: 0;
  line-height: 1.5;
}

.acd-body {
  padding: 0 4px;
}

.mono-input :deep(.el-input__wrapper) {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
}

.preset-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  border: 1px solid var(--color-border-strong);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  border-radius: var(--radius-pill);
  padding: 5px 14px;
  font-size: var(--fs-small);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.chip:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.chip.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-inverse);
}

.field-note {
  margin: 6px 0 0;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  line-height: 1.6;
}

.field-note.is-ok {
  color: var(--color-success);
}

.model-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.model-row .el-button {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.test-result {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 14px;
  border-radius: var(--radius-md);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.test-result.ok {
  background: var(--color-secondary-light);
  color: var(--color-success);
}

.test-result.fail {
  background: rgba(168, 90, 74, 0.08);
  color: var(--color-danger);
}

.enable-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 16px;
  padding: 12px 16px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
}

.enable-label {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--color-text);
}

.enable-note {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.acd-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
