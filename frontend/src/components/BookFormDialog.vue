<template>
  <el-dialog
    v-model="visible"
    width="780px"
    align-center
    class="book-form-dialog"
    :close-on-click-modal="false"
    @closed="resetForm"
  >
    <template #header>
      <div class="bf-head">
        <span class="eyebrow">{{ isEdit ? '馆藏 · REVISE ENTRY' : '馆藏 · NEW ACQUISITION' }}</span>
        <h2 class="bf-title">{{ isEdit ? '编辑图书' : '新增图书' }}</h2>
        <p class="bf-sub">{{ isEdit ? '修改保存后即时生效，ISBN 全馆唯一。' : '录入后将同步生成可借馆藏，ISBN 全馆唯一。' }}</p>
      </div>
    </template>

    <div class="bf-body" :key="dialogKey">
      <!-- 封面区 -->
      <div class="bf-cover">
        <div class="cover-frame" :class="{ 'has-img': form.coverUrl && !coverImgError }">
          <img
            v-if="form.coverUrl"
            :src="form.coverUrl"
            alt="封面预览"
            @load="coverImgError = false"
            @error="coverImgError = true"
          />
          <div v-if="!form.coverUrl || coverImgError" class="cover-empty">
            <span class="cover-char">{{ form.title ? form.title.charAt(0) : '书' }}</span>
            <span class="cover-hint">{{ coverImgError ? '封面地址无法加载' : '封面预览' }}</span>
          </div>
          <div v-else class="cover-mask">
            <el-button size="small" plain type="danger" @click="removeCover">移除封面</el-button>
          </div>
          <div v-if="coverUploading" class="cover-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>上传中…</span>
          </div>
        </div>

        <div class="cover-tabs">
          <button
            type="button"
            class="cover-tab"
            :class="{ active: coverMode === 'local' }"
            @click="setCoverMode('local')"
          >本地上传</button>
          <button
            type="button"
            class="cover-tab"
            :class="{ active: coverMode === 'link' }"
            @click="setCoverMode('link')"
          >图片链接</button>
        </div>

        <el-upload
          v-if="coverMode === 'local'"
          class="cover-upload"
          drag
          accept="image/jpeg,image/png,image/webp,image/gif"
          :show-file-list="false"
          :before-upload="beforeCoverUpload"
          :http-request="uploadCover"
        >
          <el-icon class="cover-upload-icon"><UploadFilled /></el-icon>
          <div class="cover-upload-text">拖拽图片至此，或<em>点击上传</em></div>
        </el-upload>

        <template v-else>
          <el-input v-model="coverLink" placeholder="https://…" clearable class="cover-link" />
        </template>
        <p class="cover-tip">
          {{ coverMode === 'local' ? '支持 JPG / PNG / WebP / GIF，≤ 5MB' : '粘贴图片直链，左侧实时预览' }}
        </p>
      </div>

      <!-- 表单区 -->
      <el-form
        :model="form"
        label-position="top"
        ref="formRef"
        :rules="rules"
        class="bf-form"
      >
        <div class="bf-section">基础信息</div>
        <div class="bf-fields">
          <el-form-item label="书名" prop="title" class="span-2">
            <el-input v-model="form.title" placeholder="请输入书名" />
          </el-form-item>
          <el-form-item label="作者" prop="author">
            <el-input v-model="form.author" placeholder="请输入作者" />
          </el-form-item>
          <el-form-item label="ISBN" prop="isbn">
            <el-input v-model="form.isbn" placeholder="请输入 ISBN" />
          </el-form-item>
          <el-form-item label="分类" prop="categoryId" class="span-2">
            <CategoryTreeSelect
              v-model="form.categoryId"
              :data="categories"
              placeholder="输入分类名称可模糊搜索"
            />
          </el-form-item>
        </div>

        <div class="bf-section">馆藏信息</div>
        <div class="bf-fields">
          <el-form-item label="出版社">
            <el-input v-model="form.publisher" placeholder="选填" />
          </el-form-item>
          <el-form-item label="馆藏位置">
            <el-input v-model="form.location" placeholder="如 A区1排3架" />
          </el-form-item>
          <el-form-item label="定价（元）" prop="price">
            <el-input-number
              v-model="form.price"
              :min="10"
              :max="99999"
              :precision="2"
              :step="1"
              :value-on-cleanup="10"
              controls-position="right"
              style="width: 100%"
            />
            <span class="field-tip">最低定价 ¥10.00</span>
          </el-form-item>
          <el-form-item label="馆藏数量" prop="totalCount">
            <el-input-number
              v-model="form.totalCount"
              :min="1"
              :max="9999"
              :step="1"
              controls-position="right"
              style="width: 100%"
            />
            <span class="field-tip">入库即全部可借</span>
          </el-form-item>
        </div>
      </el-form>
    </div>

    <template #footer>
      <div class="bf-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :icon="isEdit ? Check : Plus"
          :loading="submitting"
          @click="submit"
        >{{ isEdit ? '保存修改' : '确认新增' }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { Plus, Check, UploadFilled, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bookApi, uploadApi } from '@/api'
import CategoryTreeSelect from '@/components/CategoryTreeSelect.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add | edit
  book: { type: Object, default: null },
  categories: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'success'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})
const isEdit = computed(() => props.mode === 'edit')
const dialogKey = ref(0)

const formRef = ref(null)
const submitting = ref(false)

// 封面上传
const coverMode = ref('local') // local | link
const coverLink = ref('')
const coverUploading = ref(false)
const coverImgError = ref(false)

const form = reactive({
  title: '', author: '', isbn: '', categoryId: null,
  publisher: '', location: '', price: 10, totalCount: 1, coverUrl: '',
})

const rules = {
  title: [{ required: true, message: '请输入书名', trigger: 'blur' }],
  author: [{ required: true, message: '请输入作者', trigger: 'blur' }],
  isbn: [{ required: true, message: '请输入 ISBN', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  price: [{
    validator: (rule, value, callback) => {
      if (value == null) return callback(new Error('请输入定价'))
      if (Number(value) < 10) return callback(new Error('价格不能低于 10 元'))
      callback()
    },
    trigger: 'blur',
  }],
}

// 封面链接与表单同步；地址变化时重置加载错误态
watch(coverLink, (v) => {
  form.coverUrl = v.trim()
})
watch(() => form.coverUrl, () => {
  coverImgError.value = false
})

// 打开弹窗时按模式初始化表单
watch(() => props.modelValue, (v) => {
  if (!v) return
  dialogKey.value++
  if (isEdit.value && props.book) {
    Object.assign(form, {
      title: props.book.title || '',
      author: props.book.author || '',
      isbn: props.book.isbn || '',
      categoryId: props.book.categoryId ?? null,
      publisher: props.book.publisher || '',
      location: props.book.location || '',
      price: props.book.price != null ? Number(props.book.price) : 10,
      totalCount: props.book.totalCount ?? 1,
      coverUrl: props.book.coverUrl || '',
    })
    coverLink.value = props.book.coverUrl && /^https?:\/\//i.test(props.book.coverUrl)
      ? props.book.coverUrl
      : ''
    coverMode.value = /^https?:\/\//i.test(props.book.coverUrl || '') ? 'link' : 'local'
  } else {
    resetForm()
  }
  nextTick(() => formRef.value?.clearValidate())
})

const setCoverMode = (mode) => {
  coverMode.value = mode
  // 切到链接模式时回填当前封面（便于修改）
  if (mode === 'link' && form.coverUrl && !coverLink.value) {
    coverLink.value = form.coverUrl
  }
}

const beforeCoverUpload = (file) => {
  const okTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!okTypes.includes(file.type)) {
    ElMessage.error('仅支持 JPG / PNG / WebP / GIF 格式')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

const uploadCover = async ({ file }) => {
  coverUploading.value = true
  try {
    const res = await uploadApi.image(file)
    form.coverUrl = res.url
    coverLink.value = ''
    ElMessage.success('封面上传成功')
  } catch {
    // 错误已由拦截器提示
  } finally {
    coverUploading.value = false
  }
}

const removeCover = () => {
  form.coverUrl = ''
  coverLink.value = ''
  coverImgError.value = false
}

const resetForm = () => {
  Object.assign(form, {
    title: '', author: '', isbn: '', categoryId: null,
    publisher: '', location: '', price: 10, totalCount: 1, coverUrl: '',
  })
  coverLink.value = ''
  coverMode.value = 'local'
  coverImgError.value = false
  formRef.value?.clearValidate()
}

const submit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const payload = { ...form }
      if (isEdit.value) payload.id = props.book.id
      const res = isEdit.value ? await bookApi.update(payload) : await bookApi.add(payload)
      ElMessage.success(res.message || (isEdit.value ? '修改成功' : '新增图书成功'))
      visible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  })
}
</script>

<style>
/* ===== 图书新增/编辑弹窗（Editorial · 日式极简） ===== */
.book-form-dialog {
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.book-form-dialog .el-dialog__header {
  padding: 26px 32px 0;
  margin-right: 0;
}
.book-form-dialog .el-dialog__headerbtn {
  top: 22px;
  right: 22px;
}
.book-form-dialog .el-dialog__body {
  padding: 18px 32px 6px;
}
.book-form-dialog .el-dialog__footer {
  padding: 14px 32px 22px;
  border-top: 1px solid var(--color-divider);
}

/* —— 头部 —— */
.book-form-dialog .bf-head .eyebrow {
  display: block;
  margin-bottom: 6px;
}
.book-form-dialog .bf-head .bf-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h2);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  color: var(--color-text);
  margin: 0 0 6px;
}
.book-form-dialog .bf-head .bf-sub {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

/* —— 主体两栏 —— */
.book-form-dialog .bf-body {
  display: grid;
  grid-template-columns: 172px minmax(0, 1fr);
  gap: 28px;
  align-items: start;
}
.book-form-dialog .bf-cover,
.book-form-dialog .bf-form {
  animation: bf-rise var(--duration-slow) var(--ease-out) both;
}
.book-form-dialog .bf-form { animation-delay: 60ms; }
@keyframes bf-rise {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* —— 封面区 —— */
.book-form-dialog .cover-frame {
  position: relative;
  width: 100%;
  aspect-ratio: 3 / 4;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: linear-gradient(160deg, var(--color-bg-subtle), var(--color-bg-muted));
  overflow: hidden;
  transition: border-color var(--duration-fast) var(--ease-soft);
}
.book-form-dialog .cover-frame:hover { border-color: var(--color-border-strong); }
.book-form-dialog .cover-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.book-form-dialog .cover-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.book-form-dialog .cover-empty .cover-char {
  font-family: var(--font-serif);
  font-size: 44px;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
  opacity: 0.55;
}
.book-form-dialog .cover-empty .cover-hint {
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}
.book-form-dialog .cover-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(26, 24, 21, 0.45);
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-soft);
}
.book-form-dialog .cover-frame:hover .cover-mask { opacity: 1; }
.book-form-dialog .cover-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: rgba(250, 248, 245, 0.72);
  backdrop-filter: blur(2px);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
.book-form-dialog .cover-loading .el-icon {
  font-size: 20px;
  color: var(--color-primary);
}

/* —— 模式切换 —— */
.book-form-dialog .cover-tabs {
  display: flex;
  gap: 16px;
  margin: 12px 0 10px;
}
.book-form-dialog .cover-tab {
  appearance: none;
  background: none;
  border: none;
  padding: 0 0 6px;
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  cursor: pointer;
  border-bottom: 1px solid transparent;
  transition: color var(--duration-fast) var(--ease-soft),
    border-color var(--duration-fast) var(--ease-soft);
}
.book-form-dialog .cover-tab:hover { color: var(--color-text-secondary); }
.book-form-dialog .cover-tab.active {
  color: var(--color-primary);
  font-weight: 500;
  border-bottom-color: var(--color-primary);
}

/* —— 上传拖拽区 / 链接输入 —— */
.book-form-dialog .cover-upload .el-upload { width: 100%; }
.book-form-dialog .cover-upload .el-upload-dragger {
  width: 100%;
  padding: 16px 12px;
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-md);
  background: transparent;
  transition: border-color var(--duration-fast) var(--ease-soft),
    background var(--duration-fast) var(--ease-soft);
}
.book-form-dialog .cover-upload .el-upload-dragger:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}
.book-form-dialog .cover-upload-icon {
  font-size: 24px;
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}
.book-form-dialog .cover-upload-text {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
.book-form-dialog .cover-upload-text em {
  color: var(--color-primary);
  font-style: normal;
}
.book-form-dialog .cover-tip {
  margin: 8px 0 0;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* —— 表单 —— */
.book-form-dialog .bf-form .bf-section {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 12px;
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--color-text-tertiary);
}
.book-form-dialog .bf-form .bf-section:not(:first-child) { margin-top: 18px; }
.book-form-dialog .bf-form .bf-section::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--color-divider);
}
.book-form-dialog .bf-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}
.book-form-dialog .bf-fields .span-2 { grid-column: span 2; }
.book-form-dialog .bf-form .el-form-item { margin-bottom: 14px; }
.book-form-dialog .bf-form .el-form-item__label {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  line-height: 1.4;
  margin-bottom: 4px;
}
.book-form-dialog .bf-form .el-form-item__content { flex-wrap: wrap; }
.book-form-dialog .field-tip {
  display: block;
  width: 100%;
  font-size: var(--fs-caption);
  line-height: 1.4;
  margin-top: 2px;
  color: var(--color-text-tertiary);
}

/* —— 底部 —— */
.book-form-dialog .bf-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
