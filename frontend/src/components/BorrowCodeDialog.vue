<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    width="360px"
    align-center
    append-to-body
    :show-close="true"
    class="borrow-code-dialog"
  >
    <div class="code-content">
      <span class="eyebrow">{{ mode === 'success' ? '借阅成功' : '借阅码' }}</span>
      <h3 class="code-title">{{ mode === 'success' ? '借阅申请已通过' : '出示借阅码' }}</h3>
      <p v-if="bookTitle" class="book-name">{{ bookTitle }}</p>

      <!-- 二维码 -->
      <div class="qr-card">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="借阅码二维码" class="qr-img" />
        <div v-else class="qr-placeholder">
          <el-icon size="32"><Loading /></el-icon>
        </div>
      </div>

      <!-- 借阅码字符串 -->
      <div class="code-row">
        <span class="code-text">{{ borrowNo }}</span>
        <el-button text size="small" :icon="CopyDocument" @click="copyCode">复制</el-button>
      </div>

      <!-- 取书截止时间 -->
      <div v-if="pickupDeadline" class="deadline-tip">
        <el-icon><Warning /></el-icon>
        <span>请在 {{ pickupDeadline }} 前到馆取书</span>
      </div>

      <!-- 提示 -->
      <p class="hint">
        {{ showPickup ? '管理员可在此直接确认取书，或到服务台扫码确认。' : '请到图书馆服务台出示此借阅码，由管理员确认取书。' }}
      </p>
    </div>

    <template #footer>
      <el-button v-if="showPickup" type="primary" :loading="pickupLoading" @click="onPickup">确认取书</el-button>
      <el-button @click="close">{{ showPickup ? '关闭' : '我已知晓' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Warning, Loading } from '@element-plus/icons-vue'
import QRCode from 'qrcode'

const props = defineProps({
  modelValue: Boolean,
  borrowNo: String,
  pickupDeadline: String,
  bookTitle: String,
  qrPayload: String,
  mode: { type: String, default: 'success' },
  showPickup: { type: Boolean, default: false },
  pickupLoading: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'pickup'])

const qrDataUrl = ref('')

watch(
  () => [props.borrowNo, props.qrPayload],
  async () => {
    const payload = props.qrPayload || props.borrowNo
    if (!payload) {
      qrDataUrl.value = ''
      return
    }
    try {
      qrDataUrl.value = await QRCode.toDataURL(payload, {
        width: 240,
        margin: 2,
        color: { dark: '#2c2825', light: '#ffffff' },
      })
    } catch {
      qrDataUrl.value = ''
    }
  },
  { immediate: true }
)

const copyCode = async () => {
  try {
    await navigator.clipboard.writeText(props.borrowNo)
    ElMessage.success('借阅码已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

const onPickup = () => emit('pickup', props.borrowNo)

const close = () => emit('update:modelValue', false)
</script>

<style scoped>
.code-content {
  text-align: center;
  padding: var(--space-2) 0;
}

.eyebrow {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--color-primary);
}

.code-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-text);
  margin: var(--space-1) 0 var(--space-2);
}

.book-name {
  font-size: var(--fs-body);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-4);
}

.qr-card {
  display: inline-block;
  padding: var(--space-3);
  background: #ffffff;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-divider);
  margin-bottom: var(--space-4);
}

.qr-img {
  display: block;
  width: 200px;
  height: 200px;
}

.qr-placeholder {
  width: 200px;
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-tertiary);
}

.code-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.code-text {
  font-family: var(--font-mono);
  font-size: var(--fs-h4);
  font-weight: 600;
  color: var(--color-text);
  letter-spacing: 0.05em;
}

.deadline-tip {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--color-warning);
  font-size: var(--fs-caption);
  margin-bottom: var(--space-3);
}

.hint {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  line-height: 1.6;
}
</style>
