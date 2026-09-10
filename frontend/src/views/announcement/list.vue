<template>
  <div class="page announce-page" ref="pageRef">
    <header class="page-header">
      <span class="eyebrow">公告 · BULLETIN</span>
      <h1>公告通知</h1>
      <p class="desc">系统公告与消息发布。</p>
    </header>

    <div class="table-toolbar" style="margin-bottom: var(--space-4); border: none; padding: 0;">
      <div class="left"></div>
      <div class="right">
        <el-button v-if="userStore.isAdmin" type="primary" :icon="Plus" @click="addVisible = true">发布公告</el-button>
      </div>
    </div>

    <div class="announce-list">
      <article
        v-for="item in list"
        :key="item.id"
        class="card announce-card card-hover fade-in"
      >
        <div class="announce-top">
          <span v-if="item.top" class="top-badge">置顶</span>
          <span class="eyebrow">{{ formatTime(item.publishTime) }}</span>
        </div>
        <h3 class="announce-title">{{ item.title }}</h3>
        <p class="announce-content">{{ item.content }}</p>
        <div class="announce-meta">
          <span class="publisher">
            <el-icon><UserFilled /></el-icon>
            {{ item.publisherName }}
          </span>
          <div class="actions">
            <el-button text type="primary" size="small" @click="openDetail(item)">阅读全文</el-button>
            <el-button v-if="userStore.isAdmin" text type="warning" size="small" @click="onToggleTop(item)">
              {{ item.top ? '取消置顶' : '置顶' }}
            </el-button>
            <el-button v-if="userStore.isAdmin" text type="danger" size="small" @click="onDelete(item)">删除</el-button>
          </div>
        </div>
      </article>

      <div v-if="!list.length" class="empty-state">暂无公告</div>
    </div>

    <div v-if="total > size" class="table-pagination">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[5, 10, 20]"
        layout="total, prev, pager, next"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 发布公告弹窗 -->
    <el-dialog v-model="addVisible" title="发布公告" width="600px" align-center>
      <el-form :model="addForm" label-width="80px" ref="addFormRef" :rules="addRules">
        <el-form-item label="标题" prop="title">
          <el-input v-model="addForm.title" placeholder="请输入公告标题" />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input
            v-model="addForm.content"
            type="textarea"
            :rows="5"
            placeholder="请输入公告内容"
          />
        </el-form-item>
        <el-form-item label="置顶">
          <el-switch v-model="addForm.top" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="addLoading" @click="submitAdd">发布</el-button>
      </template>
    </el-dialog>

    <!-- 公告详情弹窗 -->
    <el-dialog v-model="detailVisible" title="公告详情" width="600px" align-center>
      <div v-if="detailData" class="detail-content">
        <div class="detail-top">
          <span v-if="detailData.top" class="top-badge">置顶</span>
          <span class="eyebrow">{{ formatTime(detailData.publishTime) }}</span>
        </div>
        <h2 class="detail-title">{{ detailData.title }}</h2>
        <div class="detail-meta">
          <el-icon><UserFilled /></el-icon>
          <span>{{ detailData.publisherName }}</span>
        </div>
        <div class="detail-body">{{ detailData.content }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { UserFilled, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { announcementApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading.js'
import { useScrollRestore } from '@/composables/useScrollRestore'
import { useSnapshotRefresh } from '@/composables/useSnapshotRefresh'

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: 'AnnouncementList' })

const pageRef = ref(null)
useScrollRestore(pageRef)

const userStore = useUserStore()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(5)

// 发布公告
const addVisible = ref(false)
const addLoading = ref(false)
const addFormRef = ref(null)
const addForm = reactive({ title: '', content: '', top: false })
const addRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur' }],
}

// 详情
const detailVisible = ref(false)
const detailData = ref(null)

const fetchSnapshot = async () => {
  const data = await announcementApi.list({ page: page.value, size: size.value })
  return { list: data.list, total: data.total }
}

const applySnapshot = (s) => {
  list.value = s.list
  total.value = s.total
}

const { reload } = useSnapshotRefresh({
  fetch: fetchSnapshot,
  apply: applySnapshot,
})

// 页面自身的加载（首屏、翻页、发布后）带遮罩；激活比对走静默路径，不闪遮罩
const load = async () => {
  showPageLoading()
  try {
    await reload()
  } finally {
    hidePageLoading()
  }
}

const onSizeChange = () => {
  page.value = 1
  load()
}

const formatTime = (t) => {
  if (!t) return ''
  return t.substring(0, 10)
}

const submitAdd = async () => {
  if (!addFormRef.value) return
  await addFormRef.value.validate(async (valid) => {
    if (!valid) return
    addLoading.value = true
    try {
      const res = await announcementApi.add({ ...addForm })
      ElMessage.success(res.message || '发布成功')
      addVisible.value = false
      Object.assign(addForm, { title: '', content: '', top: false })
      page.value = 1
      await load()
    } finally {
      addLoading.value = false
    }
  })
}

const openDetail = async (item) => {
  const data = await announcementApi.detail(item.id)
  detailData.value = data
  detailVisible.value = true
}

const onDelete = async (item) => {
  try {
    await ElMessageBox.confirm(`确认删除公告「${item.title}」？`, '删除确认', { type: 'warning' })
    const res = await announcementApi.delete({ id: item.id })
    ElMessage.success(res.message || '删除成功')
    await load()
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已由拦截器提示
    }
  }
}

const onToggleTop = async (item) => {
  try {
    const res = await announcementApi.toggleTop({ id: item.id })
    ElMessage.success(res.message || (item.top ? '已取消置顶' : '已置顶'))
    await load()
  } catch {
    // 错误已由拦截器提示
  }
}

onMounted(load)
</script>

<style scoped>
.announce-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.announce-card {
  padding: var(--space-5);
}

.announce-top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.top-badge {
  display: inline-block;
  padding: 2px 8px;
  background: rgba(168, 90, 74, 0.12);
  color: var(--color-danger);
  border-radius: var(--radius-sm);
  font-size: var(--fs-caption);
  font-weight: 500;
}

.announce-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  line-height: var(--lh-snug);
  margin-bottom: var(--space-3);
  color: var(--color-text);
}

.announce-content {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-4);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.announce-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
}

.publisher {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

.actions {
  display: flex;
  gap: var(--space-2);
}

/* 详情弹窗 */
.detail-content {
  padding: var(--space-2) 0;
}

.detail-top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.detail-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h2);
  font-weight: 600;
  line-height: var(--lh-snug);
  margin-bottom: var(--space-3);
  color: var(--color-text);
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
}

.detail-body {
  font-size: var(--fs-body);
  line-height: var(--lh-relaxed);
  color: var(--color-text-secondary);
  white-space: pre-wrap;
}

.table-pagination {
  display: flex;
  justify-content: center;
  padding: var(--space-5) 0;
}
</style>
