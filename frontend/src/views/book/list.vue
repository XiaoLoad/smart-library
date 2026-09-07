<template>
  <div class="page book-list-page">
    <header class="page-header">
      <span class="eyebrow">馆藏 · COLLECTION</span>
      <h1>图书检索</h1>
      <p class="desc">在 {{ total }} 册馆藏中查找您要的书。</p>
    </header>

    <div class="book-layout">
      <!-- 分类树侧边 -->
      <aside class="category-side">
        <div class="card category-card">
          <div class="card-title">
            <el-icon><Collection /></el-icon>
            <span>分类导航</span>
          </div>
          <el-input
            v-model="categoryFilter"
            placeholder="搜索分类"
            :prefix-icon="Search"
            clearable
            size="small"
            class="category-filter"
          />
          <el-tree
            :data="filteredCategories"
            node-key="id"
            :props="{ label: 'categoryName', children: 'children' }"
            :expand-on-click-node="false"
            default-expand-all
            @node-click="onCategoryClick"
            class="category-tree"
          >
            <template #default="{ node, data }">
              <span class="tree-node">
                <span class="tree-label">{{ data.categoryName }}</span>
                <span class="tree-code">{{ data.categoryCode }}</span>
              </span>
            </template>
          </el-tree>
          <div class="all-books" :class="{ active: !selectedCategory }" @click="clearCategory">
            <el-icon><Notebook /></el-icon>
            <span>全部图书</span>
            <span class="count">{{ total }}</span>
          </div>
        </div>
      </aside>

      <!-- 图书列表 -->
      <section class="book-main fade-in" v-if="books.length">
        <div class="data-table">
          <div class="table-toolbar">
            <div class="left">
              <el-input
                v-model="search"
                placeholder="书名 / 作者 / ISBN"
                :prefix-icon="Search"
                clearable
                class="search-input"
                @keyup.enter="loadBooks"
                @clear="loadBooks"
              />
              <el-button @click="loadBooks">搜索</el-button>
            </div>
            <div class="right">
              <span v-if="!userStore.isAdmin && quota" class="quota-tip">
                剩余可借 {{ quota.remaining }} 本
              </span>
              <el-button v-if="userStore.isAdmin" type="primary" :icon="Plus" @click="addBookVisible = true">新增图书</el-button>
            </div>
          </div>

          <el-table :data="books" style="width: 100%">
            <el-table-column label="书名" min-width="220">
              <template #default="{ row }">
                <div class="book-cell">
                  <div class="book-cover">
                    <span class="cover-char">{{ row.title.charAt(0) }}</span>
                  </div>
                  <div class="book-info">
                    <div class="book-title">{{ row.title }}</div>
                    <div class="book-isbn">{{ row.isbn }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="author" label="作者" width="160" />
            <el-table-column prop="publisher" label="出版社" width="180" />
            <el-table-column label="馆藏位置" width="120">
              <template #default="{ row }">
                <span class="mono-text">{{ row.location }}</span>
              </template>
            </el-table-column>
            <el-table-column label="库存" width="160">
              <template #default="{ row }">
                <div class="stock-cell">
                  <div class="stock-bar">
                    <div
                      class="stock-bar__fill"
                      :class="stockClass(row)"
                      :style="{ width: stockPercent(row) + '%' }"
                    ></div>
                  </div>
                  <span class="stock-text">
                    {{ row.availableCount }} / {{ row.totalCount }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" size="small" @click="openDetail(row)">详情</el-button>
                <el-button
                  v-if="!userStore.isAdmin"
                  text
                  type="primary"
                  size="small"
                  :disabled="row.availableCount === 0 || !quota?.canBorrow"
                  :loading="borrowingId === row.id"
                  @click="onBorrow(row)"
                >借阅</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="table-pagination">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              background
              @current-change="loadBooks"
              @size-change="onSizeChange"
            />
          </div>
        </div>
      </section>
    </div>

    <!-- 借阅码弹窗 -->
    <BorrowCodeDialog
      v-model="codeVisible"
      :borrowNo="codeData.borrowNo"
      :pickupDeadline="codeData.pickupDeadline"
      :bookTitle="codeData.bookTitle"
      :qrPayload="codeData.qrPayload"
      mode="success"
    />

    <!-- 新增图书弹窗 -->
    <el-dialog v-model="addBookVisible" title="新增图书" width="560px" align-center>
      <el-form :model="addBookForm" label-width="90px" ref="addBookFormRef" :rules="addBookRules">
        <el-form-item label="书名" prop="title">
          <el-input v-model="addBookForm.title" placeholder="请输入书名" />
        </el-form-item>
        <el-form-item label="作者" prop="author">
          <el-input v-model="addBookForm.author" placeholder="请输入作者" />
        </el-form-item>
        <el-form-item label="ISBN" prop="isbn">
          <el-input v-model="addBookForm.isbn" placeholder="请输入 ISBN" />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-tree-select
            v-model="addBookForm.categoryId"
            :data="categoryTreeData"
            :props="{ label: 'categoryName', value: 'id', children: 'children' }"
            check-strictly
            placeholder="请选择分类"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="出版社">
          <el-input v-model="addBookForm.publisher" placeholder="请输入出版社" />
        </el-form-item>
        <el-form-item label="馆藏位置">
          <el-input v-model="addBookForm.location" placeholder="如 A区1排3架" />
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="addBookForm.price" :min="0" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="馆藏数量">
          <el-input-number v-model="addBookForm.totalCount" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addBookVisible = false">取消</el-button>
        <el-button type="primary" :loading="addBookLoading" @click="submitAddBook">确认新增</el-button>
      </template>
    </el-dialog>

    <!-- 图书详情弹窗 -->
    <el-dialog v-model="detailVisible" title="图书详情" width="600px" align-center>
      <div v-if="detailData" class="book-detail">
        <div class="detail-header">
          <div class="detail-cover">
            <span class="cover-char">{{ detailData.title?.charAt(0) }}</span>
          </div>
          <div class="detail-title-area">
            <h2 class="detail-title">{{ detailData.title }}</h2>
            <p class="detail-author">{{ detailData.author }}</p>
            <span class="status-tag" :class="stockClass(detailData)">
              {{ detailData.availableCount > 0 ? '可借' : '已借完' }}
            </span>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="detail-desc">
          <el-descriptions-item label="ISBN">{{ detailData.isbn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="出版社">{{ detailData.publisher || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ findCategoryName(detailData.categoryId) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="馆藏位置">{{ detailData.location || '-' }}</el-descriptions-item>
          <el-descriptions-item label="价格">{{ detailData.price != null ? '¥' + detailData.price : '-' }}</el-descriptions-item>
          <el-descriptions-item label="出版日期">{{ detailData.publishDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="馆藏总量">{{ detailData.totalCount ?? '-' }} 册</el-descriptions-item>
          <el-descriptions-item label="可借数量">
            <span :class="stockClass(detailData)">{{ detailData.availableCount ?? '-' }} 册</span>
          </el-descriptions-item>
          <el-descriptions-item label="入库时间" :span="2">{{ formatTime(detailData.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="图书简介" :span="2">
            <span class="detail-description">{{ detailData.description || '暂无简介' }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="!userStore.isAdmin && detailData?.availableCount > 0"
          type="primary"
          @click="detailBorrow"
        >借阅此书</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive } from 'vue'
import { Search, Plus, Collection, Notebook } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bookApi, borrowApi } from '@/api'
import { useUserStore } from '@/stores/user'
import BorrowCodeDialog from '@/components/BorrowCodeDialog.vue'

const userStore = useUserStore()
const books = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const search = ref('')
const categories = ref([])
const categoryFilter = ref('')
const selectedCategory = ref(null)

// 借阅额度
const quota = ref(null)
const borrowingId = ref(null)
const codeVisible = ref(false)
const codeData = ref({ borrowNo: '', pickupDeadline: '', bookTitle: '', qrPayload: '' })

// 新增图书
const addBookVisible = ref(false)
const addBookLoading = ref(false)
const addBookFormRef = ref(null)

// 图书详情
const detailVisible = ref(false)
const detailData = ref(null)
const addBookForm = reactive({
  title: '', author: '', isbn: '', categoryId: null,
  publisher: '', location: '', price: 0, totalCount: 1,
})
const addBookRules = {
  title: [{ required: true, message: '请输入书名', trigger: 'blur' }],
  author: [{ required: true, message: '请输入作者', trigger: 'blur' }],
  isbn: [{ required: true, message: '请输入 ISBN', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
}
// el-tree-select 需要带 children 字段的数据
const categoryTreeData = computed(() => categories.value)

const filteredCategories = computed(() => {
  if (!categoryFilter.value) return categories.value
  const filter = (nodes) => {
    return nodes
      .map((n) => {
        const children = n.children ? filter(n.children) : []
        const match = n.categoryName.includes(categoryFilter.value)
        if (match || children.length) {
          return { ...n, children }
        }
        return null
      })
      .filter(Boolean)
  }
  return filter(categories.value)
})

const loadBooks = async () => {
  const data = await bookApi.list({
    page: page.value,
    size: size.value,
    keyword: search.value,
    categoryId: selectedCategory.value,
  })
  books.value = data.list
  total.value = data.total
}

const onSizeChange = () => {
  page.value = 1
  loadBooks()
}

const loadCategories = async () => {
  const data = await bookApi.categoryTree()
  categories.value = data
}

const loadQuota = async () => {
  if (userStore.isAdmin) return
  try {
    quota.value = await borrowApi.quota()
  } catch {
    // 静默失败，不阻塞页面
  }
}

const onBorrow = async (row) => {
  if (userStore.isAdmin) return
  try {
    await ElMessageBox.confirm(
      `确认借阅《${row.title}》？\n借阅成功后请及时到馆取书。`,
      '借阅确认',
      { type: 'warning', confirmButtonText: '确认借阅', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  borrowingId.value = row.id
  try {
    const res = await borrowApi.apply({ bookId: row.id })
    codeData.value = {
      borrowNo: res.borrowNo,
      pickupDeadline: res.pickupDeadline,
      bookTitle: row.title,
      qrPayload: res.qrPayload,
    }
    codeVisible.value = true
    ElMessage.success('借阅申请成功，请到馆取书')
    await Promise.all([loadBooks(), loadQuota()])
  } catch {
    // 错误已由拦截器提示
  } finally {
    borrowingId.value = null
  }
}

// 图书详情
const openDetail = (row) => {
  detailData.value = row
  detailVisible.value = true
}

const detailBorrow = () => {
  if (!detailData.value) return
  detailVisible.value = false
  onBorrow(detailData.value)
}

// 在分类树中查找分类名称
const findCategoryName = (categoryId) => {
  if (!categoryId) return ''
  const find = (nodes) => {
    for (const n of nodes) {
      if (n.id === categoryId) return n.categoryName
      if (n.children) {
        const r = find(n.children)
        if (r) return r
      }
    }
    return ''
  }
  return find(categories.value)
}

const formatTime = (t) => {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 19)
}

const onCategoryClick = (node) => {
  selectedCategory.value = node.id
  page.value = 1
  loadBooks()
}

const clearCategory = () => {
  selectedCategory.value = null
  page.value = 1
  loadBooks()
}

const stockPercent = (row) => {
  if (!row.totalCount) return 0
  return Math.round((row.availableCount / row.totalCount) * 100)
}

const stockClass = (row) => {
  const p = stockPercent(row)
  if (p === 0) return 'danger'
  if (p < 30) return 'warning'
  return ''
}

// 提交新增图书
const submitAddBook = async () => {
  if (!addBookFormRef.value) return
  await addBookFormRef.value.validate(async (valid) => {
    if (!valid) return
    addBookLoading.value = true
    try {
      const res = await bookApi.add({ ...addBookForm })
      ElMessage.success(res.message || '新增图书成功')
      addBookVisible.value = false
      // 重置表单
      Object.assign(addBookForm, {
        title: '', author: '', isbn: '', categoryId: null,
        publisher: '', location: '', price: 0, totalCount: 1,
      })
      // 刷新到最后一页查看新书
      await loadBooks()
    } finally {
      addBookLoading.value = false
    }
  })
}

onMounted(async () => {
  await loadCategories()
  await Promise.all([loadBooks(), loadQuota()])
})
</script>

<style scoped>
.book-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--space-4);
  min-width: 0;
}

/* 主区域必须允许收缩，防止 grid 子项膨胀导致横向溢出 */
.book-main {
  min-width: 0;
  overflow: hidden;
}

/* 分类侧边 */
.category-card {
  padding: var(--space-4);
  position: sticky;
  top: calc(var(--header-height) + var(--space-4));
}

.card-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  margin-bottom: var(--space-4);
}

.category-filter { margin-bottom: var(--space-3); }

.category-tree {
  margin-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-divider);
  padding-bottom: var(--space-3);
}

.tree-node {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding-right: var(--space-2);
}

.tree-label {
  font-size: var(--fs-small);
  color: var(--color-text);
}

.tree-code {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.all-books {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: var(--radius-md);
  transition: all var(--duration-fast) var(--ease-soft);
}

.all-books:hover {
  background: var(--color-bg-subtle);
  color: var(--color-text);
}

.all-books.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}

.all-books .count {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
}

/* 图书表格 */
.book-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.book-cover {
  width: 36px;
  height: 48px;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, var(--color-primary-light), var(--color-secondary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.cover-char {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-primary);
}

.book-info { min-width: 0; flex: 1; }
.book-info .book-title {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.book-info .book-isbn {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.stock-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.stock-text {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  min-width: 50px;
}

@media (max-width: 1024px) {
  .book-layout { grid-template-columns: 1fr; }
  .category-card { position: static; }
}

.quota-tip {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin-right: var(--space-2);
}

/* 图书详情弹窗 */
.book-detail { padding: 0 var(--space-2); }

.detail-header {
  display: flex;
  gap: var(--space-4);
  align-items: center;
  margin-bottom: var(--space-5);
}

.detail-cover {
  width: 56px;
  height: 72px;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, var(--color-primary-light), var(--color-secondary-light));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.detail-cover .cover-char {
  font-family: var(--font-serif);
  font-size: 28px;
  font-weight: 600;
  color: var(--color-primary);
}

.detail-title-area { min-width: 0; }

.detail-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 4px;
}

.detail-author {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin: 0 0 6px;
}

.detail-desc { margin-bottom: var(--space-2); }

.detail-description {
  display: block;
  line-height: 1.6;
  color: var(--color-text-secondary);
  font-size: var(--fs-small);
}
</style>
