<template>
  <el-dialog
    v-model="visible"
    width="648px"
    align-center
    class="book-form-dialog"
    :close-on-click-modal="false"
    @closed="resetForm"
  >
    <template #header>
      <div class="bf-head">
        <div class="bf-head-main">
          <span class="eyebrow">{{ isEdit ? '馆藏 · REVISE ENTRY' : '馆藏 · NEW ACQUISITION' }}</span>
          <h2 class="bf-title">{{ isEdit ? '编辑图书' : '新增图书' }}</h2>
        </div>
        <p class="bf-sub">{{ isEdit ? '保存后即时生效' : '入库即全部可借' }} · ISBN 全馆唯一</p>
      </div>
    </template>

    <div class="bf-body" :key="dialogKey">
      <!-- 封面：缩略图 + 紧凑操作 -->
      <aside class="bf-cover">
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
            <span class="cover-hint">{{ coverImgError ? '地址无效' : '封面' }}</span>
          </div>
          <button
            v-if="form.coverUrl && !coverImgError"
            type="button"
            class="cover-remove"
            aria-label="移除封面"
            @click="removeCover"
          >×</button>
          <div v-if="coverUploading" class="cover-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
          </div>
        </div>

        <div class="cover-switch" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="coverMode === 'local'"
            :class="{ active: coverMode === 'local' }"
            @click="setCoverMode('local')"
          >上传</button>
          <button
            type="button"
            role="tab"
            :aria-selected="coverMode === 'link'"
            :class="{ active: coverMode === 'link' }"
            @click="setCoverMode('link')"
          >链接</button>
        </div>

        <el-upload
          v-if="coverMode === 'local'"
          class="cover-upload-inline"
          accept="image/jpeg,image/png,image/webp,image/gif"
          :show-file-list="false"
          :before-upload="beforeCoverUpload"
          :http-request="uploadCover"
        >
          <button type="button" class="cover-action">
            <el-icon><UploadFilled /></el-icon>{{ form.coverUrl ? '更换封面' : '选择图片' }}
          </button>
        </el-upload>

        <el-input
          v-else
          v-model="coverLink"
          size="small"
          placeholder="图片直链 https://…"
          clearable
          class="cover-link"
        />

        <p class="cover-tip">{{ coverMode === 'local' ? 'JPG / PNG / WebP / GIF，≤ 5MB' : '粘贴直链，左侧实时预览' }}</p>
      </aside>

      <!-- 表单：紧凑三组 -->
      <el-form
        :model="form"
        label-position="top"
        ref="formRef"
        :rules="rules"
        class="bf-form"
      >
        <div class="bf-group">
          <span class="bf-group-no">01</span>
          <span class="bf-group-name">书目</span>
          <span class="bf-group-rule"></span>
        </div>
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
          <el-form-item label="分类" prop="categoryId">
            <CategoryTreeSelect
              v-model="form.categoryId"
              :data="categories"
              placeholder="可模糊搜索"
            />
          </el-form-item>
          <el-form-item label="出版社">
            <el-input v-model="form.publisher" placeholder="选填" />
          </el-form-item>
        </div>

        <div class="bf-group">
          <span class="bf-group-no">02</span>
          <span class="bf-group-name">馆藏</span>
          <span class="bf-group-rule"></span>
        </div>
        <div class="bf-fields cols-3">
          <el-form-item label="馆藏位置">
            <el-input v-model="form.location" placeholder="如 A区1排3架" />
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
          </el-form-item>
        </div>

        <div class="bf-group">
          <span class="bf-group-no">03</span>
          <span class="bf-group-name">简介</span>
          <span class="bf-group-rule"></span>
        </div>
        <el-form-item label="内容简介" class="bf-desc">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            resize="none"
            placeholder="内容主旨、作者背景与阅读价值，选填；将展示在图书详情页"
          />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <div class="bf-footer">
        <span class="bf-foot-note">{{ isEdit ? '修改保存后即时生效' : '录入后生成可借馆藏' }}</span>
        <div class="bf-foot-actions">
          <el-button @click="visible = false">取消</el-button>
          <el-button
            type="primary"
            :icon="isEdit ? Check : Plus"
            :loading="submitting"
            @click="submit"
          >{{ isEdit ? '保存修改' : '确认新增' }}</el-button>
        </div>
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
  description: '',
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
      description: props.book.description || '',
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
    description: '',
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
/* 图书新增/编辑弹窗 · 紧凑版（书目登记卡） */
.book-form-dialog {
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  max-width: calc(100vw - 32px);
}

.book-form-dialog .el-dialog__header {
  padding: 20px 24px 0;
  margin-right: 0;
}

.book-form-dialog .el-dialog__headerbtn {
  top: 16px;
  right: 16px;
}

.book-form-dialog .el-dialog__body {
  padding: 14px 24px 2px;
  max-height: 66vh;
  overflow-y: auto;
}

.book-form-dialog .el-dialog__footer {
  padding: 10px 24px 16px;
  border-top: 1px solid var(--color-divider);
}

/* —— 头部 —— */
.book-form-dialog .bf-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.book-form-dialog .bf-head .eyebrow {
  display: block;
  margin-bottom: 4px;
}

.book-form-dialog .bf-head .bf-title {
  font-family: var(--font-serif);
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  line-height: 1.15;
  color: var(--color-text);
  margin: 0;
}

.book-form-dialog .bf-head .bf-sub {
  margin: 0 0 3px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  text-align: right;
  flex-shrink: 0;
}

/* —— 主体：封面窄栏 + 表单 —— */
.book-form-dialog .bf-body {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.book-form-dialog .bf-cover,
.book-form-dialog .bf-form {
  animation: bf-rise var(--duration-slow) var(--ease-out) both;
}

.book-form-dialog .bf-form {
  animation-delay: 50ms;
}

@keyframes bf-rise {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.book-form-dialog .bf-cover {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 18px;
  border-right: 1px solid var(--color-divider);
}

/* 封面缩略图 */
.book-form-dialog .cover-frame {
  position: relative;
  width: 100%;
  max-width: 118px;
  aspect-ratio: 3 / 4;
  border: 1px solid var(--color-border);
  border-radius: 3px;
  background: linear-gradient(160deg, var(--color-bg-subtle), var(--color-bg-muted));
  overflow: hidden;
  box-shadow: 0 10px 20px -16px rgba(31, 27, 22, 0.5);
  transition: border-color var(--duration-fast) var(--ease-soft);
}

.book-form-dialog .cover-frame:hover {
  border-color: var(--color-border-strong);
}

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
  gap: 6px;
}

.book-form-dialog .cover-empty .cover-char {
  font-family: var(--font-serif);
  font-size: 2rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
  opacity: 0.55;
}

.book-form-dialog .cover-empty .cover-hint {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: var(--color-text-tertiary);
}

.book-form-dialog .cover-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(26, 24, 21, 0.62);
  color: #faf8f5;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-soft);
}

.book-form-dialog .cover-frame:hover .cover-remove {
  opacity: 1;
}

.book-form-dialog .cover-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(250, 248, 245, 0.74);
  backdrop-filter: blur(2px);
}

.book-form-dialog .cover-loading .el-icon {
  font-size: 18px;
  color: var(--color-primary);
}

/* 上传 / 链接 切换 */
.book-form-dialog .cover-switch {
  display: flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.book-form-dialog .cover-switch button {
  flex: 1;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 2px;
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: 10px;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.book-form-dialog .cover-switch button:hover {
  color: var(--color-text-secondary);
}

.book-form-dialog .cover-switch button.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 600;
}

/* 上传按钮 / 链接输入 */
.book-form-dialog .cover-upload-inline,
.book-form-dialog .cover-upload-inline .el-upload {
  width: 100%;
}

.book-form-dialog .cover-action {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  height: 26px;
  padding: 0;
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 10px;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.book-form-dialog .cover-action:hover {
  border-color: var(--color-primary);
  border-style: solid;
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.book-form-dialog .cover-link {
  width: 100%;
}

.book-form-dialog .cover-tip {
  margin: 0;
  font-size: 9px;
  line-height: 1.45;
  color: var(--color-text-tertiary);
}

/* —— 表单：紧凑分组 —— */
.book-form-dialog .bf-group {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
}

.book-form-dialog .bf-group:not(:first-child) {
  margin-top: 12px;
}

.book-form-dialog .bf-group-no {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.12em;
  color: var(--color-text-tertiary);
}

.book-form-dialog .bf-group-name {
  font-family: var(--font-serif);
  font-size: var(--fs-caption);
  letter-spacing: 0.08em;
  color: var(--color-text-secondary);
}

.book-form-dialog .bf-group-rule {
  flex: 1;
  height: 1px;
  background: var(--color-divider);
}

.book-form-dialog .bf-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 14px;
}

.book-form-dialog .bf-fields.cols-3 {
  grid-template-columns: repeat(3, 1fr);
}

.book-form-dialog .bf-fields .span-2 {
  grid-column: span 2;
}

.book-form-dialog .bf-form .el-form-item {
  margin-bottom: 10px;
}

.book-form-dialog .bf-form .el-form-item__label {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  line-height: 1.3;
  margin-bottom: 3px;
  padding: 0;
}

.book-form-dialog .bf-form .bf-desc {
  margin-bottom: 2px;
}

.book-form-dialog .bf-form .bf-desc .el-textarea__inner {
  font-size: var(--fs-small);
}

/* —— 底部 —— */
.book-form-dialog .bf-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.book-form-dialog .bf-foot-note {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.book-form-dialog .bf-foot-actions {
  display: flex;
  gap: 10px;
}

/* —— 窄屏 —— */
@media (max-width: 640px) {
  .book-form-dialog .el-dialog__body {
    max-height: 70vh;
    padding: 12px 16px 2px;
  }

  .book-form-dialog .el-dialog__header {
    padding: 16px 16px 0;
  }

  .book-form-dialog .el-dialog__footer {
    padding: 10px 16px 14px;
  }

  .book-form-dialog .bf-head .bf-sub {
    display: none;
  }

  .book-form-dialog .bf-body {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .book-form-dialog .bf-cover {
    flex-direction: row;
    align-items: flex-start;
    gap: 10px;
    padding-right: 0;
    padding-bottom: 12px;
    border-right: none;
    border-bottom: 1px solid var(--color-divider);
    flex-wrap: wrap;
  }

  .book-form-dialog .cover-frame {
    width: 84px;
    flex-shrink: 0;
  }

  .book-form-dialog .cover-switch {
    width: 84px;
    flex-shrink: 0;
  }

  .book-form-dialog .cover-upload-inline,
  .book-form-dialog .cover-link {
    flex: 1;
    min-width: 120px;
  }

  .book-form-dialog .cover-tip {
    flex-basis: 100%;
  }

  .book-form-dialog .bf-fields,
  .book-form-dialog .bf-fields.cols-3 {
    grid-template-columns: 1fr;
  }

  .book-form-dialog .bf-fields .span-2 {
    grid-column: auto;
  }
}
</style>
