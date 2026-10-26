<template>
  <div class="page book-detail-page">
    <!-- 面包屑使用顶部 header 的全局面包屑（meta.crumbs） -->

    <!-- 加载中 -->
    <div v-if="loading" class="detail-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在调取馆藏档案…</span>
    </div>

    <!-- 未找到 -->
    <div v-else-if="!book" class="detail-empty fade-in">
      <span class="empty-char">∅</span>
      <p>未找到该图书的馆藏档案</p>
      <el-button text type="primary" @click="$router.push('/book/list')">← 返回馆藏图书</el-button>
    </div>

    <!-- 详情主体 -->
    <article v-else class="detail-grid">
      <!-- 左：藏书票卡 -->
      <aside class="plate fade-in">
        <div class="plate-card">
          <div class="plate-cover" :class="{ 'no-img': !book.coverUrl }">
            <img v-if="book.coverUrl" :src="book.coverUrl" :alt="book.title" />
            <span v-else class="plate-char">{{ book.title?.charAt(0) }}</span>
          </div>

          <div class="plate-price">
            <span class="price-cny">¥</span>
            <span class="price-num">{{ book.price ?? '—' }}</span>
            <span class="price-unit">定价</span>
          </div>

          <div class="plate-stock">
            <div class="stock-bar">
              <div class="stock-bar__fill" :class="stockClass(book)" :style="{ width: stockPercent(book) + '%' }"></div>
            </div>
            <div class="stock-line">
              <span>可借</span>
              <span class="mono">{{ book.availableCount }} / {{ book.totalCount }}</span>
            </div>
          </div>

          <div class="plate-actions">
            <template v-if="userStore.isAdmin">
              <el-button type="primary" class="act-btn" @click="openEdit">编辑馆藏</el-button>
              <p class="act-tip">修改馆藏数量后将自动同步可借数</p>
            </template>
            <template v-else>
              <el-button
                type="primary"
                class="act-btn"
                :disabled="book.availableCount === 0 || !quota?.canBorrow"
                :loading="borrowing"
                @click="onBorrow"
              >{{ book.availableCount === 0 ? '暂无可借' : '借阅此书' }}</el-button>
              <p v-if="quota" class="act-tip">本学期剩余可借 {{ quota.remaining }} 本</p>
            </template>
          </div>
        </div>
      </aside>

      <!-- 右：目录档案 -->
      <section class="catalog">
        <header class="catalog-head fade-in d1">
          <span class="eyebrow">馆藏 · CATALOG NO.{{ String(book.id).padStart(4, '0') }}</span>
          <h1 class="catalog-title">{{ book.title }}</h1>
          <p class="catalog-author">
            {{ book.author || '佚名' }}
            <template v-if="book.publisher"> · {{ book.publisher }}</template>
          </p>
          <div class="catalog-chips">
            <span v-if="categoryName" class="chip">{{ categoryName }}</span>
            <span v-if="book.location" class="chip mono-chip">📍 {{ book.location }}</span>
            <span class="chip" :class="book.availableCount > 0 ? 'chip-ok' : 'chip-off'">
              {{ book.availableCount > 0 ? '可借' : '已借完' }}
            </span>
          </div>
        </header>

        <div class="catalog-section fade-in d2">
          <div class="section-label">简介 <i>ABOUT</i></div>
          <p class="catalog-desc">{{ book.description || '暂无简介。' }}</p>
        </div>

        <div class="catalog-section fade-in d3">
          <div class="section-label">档案 <i>RECORDS</i></div>
          <dl class="specs">
            <div class="spec">
              <dt>ISBN</dt>
              <dd class="mono">{{ book.isbn || '—' }}</dd>
            </div>
            <div class="spec">
              <dt>出版日期</dt>
              <dd>{{ book.publishDate || '—' }}</dd>
            </div>
            <div class="spec">
              <dt>所属分类</dt>
              <dd>{{ categoryName || '—' }}</dd>
            </div>
            <div class="spec">
              <dt>馆藏位置</dt>
              <dd class="mono">{{ book.location || '—' }}</dd>
            </div>
            <div class="spec">
              <dt>馆藏总量</dt>
              <dd>{{ book.totalCount ?? '—' }} 册</dd>
            </div>
            <div class="spec">
              <dt>可借数量</dt>
              <dd :class="book.availableCount > 0 ? 'ok' : 'off'">{{ book.availableCount ?? '—' }} 册</dd>
            </div>
            <div class="spec wide">
              <dt>入库时间</dt>
              <dd>{{ formatTime(book.createTime) }}</dd>
            </div>
          </dl>
        </div>
      </section>
    </article>

    <!-- 借阅码弹窗 -->
    <BorrowCodeDialog
      v-model="codeVisible"
      :borrowNo="codeData.borrowNo"
      :pickupDeadline="codeData.pickupDeadline"
      :bookTitle="codeData.bookTitle"
      :qrPayload="codeData.qrPayload"
      mode="success"
    />

    <!-- 编辑弹窗（复用通用组件） -->
    <BookFormDialog
      v-model="editVisible"
      mode="edit"
      :book="book"
      :categories="categories"
      @success="loadDetail"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bookApi, borrowApi } from '@/api'
import { useUserStore } from '@/stores/user'
import BorrowCodeDialog from '@/components/BorrowCodeDialog.vue'
import BookFormDialog from '@/components/BookFormDialog.vue'

const route = useRoute()
const userStore = useUserStore()

const book = ref(null)
const categories = ref([])
const loading = ref(true)

const quota = ref(null)
const borrowing = ref(false)
const codeVisible = ref(false)
const codeData = ref({ borrowNo: '', pickupDeadline: '', bookTitle: '', qrPayload: '' })

const editVisible = ref(false)

const categoryName = computed(() => {
  if (!book.value?.categoryId) return ''
  const find = (nodes) => {
    for (const n of nodes) {
      if (n.id === book.value.categoryId) return n.categoryName
      if (n.children) {
        const r = find(n.children)
        if (r) return r
      }
    }
    return ''
  }
  return find(categories.value)
})

const loadDetail = async () => {
  loading.value = true
  try {
    book.value = await bookApi.detail(route.params.id)
  } catch {
    book.value = null
  } finally {
    loading.value = false
  }
  // 同步全局面包屑第三级与浏览器标签标题
  const crumbs = route.meta.crumbs
  if (crumbs?.length) {
    crumbs[crumbs.length - 1].title = book.value ? `${book.value.title} 详情` : '图书详情'
  }
  document.title = book.value ? `${book.value.title} · 智图` : '图书详情 · 智图'
}

const loadQuota = async () => {
  if (userStore.isAdmin) return
  try {
    quota.value = await borrowApi.quota()
  } catch {
    // 静默失败
  }
}

const onBorrow = async () => {
  if (!book.value) return
  try {
    await ElMessageBox.confirm(
      `确认借阅《${book.value.title}》？\n借阅成功后请及时到馆取书。`,
      '借阅确认',
      { type: 'warning', confirmButtonText: '确认借阅', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  borrowing.value = true
  try {
    const res = await borrowApi.apply({ bookId: book.value.id })
    codeData.value = {
      borrowNo: res.borrowNo,
      pickupDeadline: res.pickupDeadline,
      bookTitle: book.value.title,
      qrPayload: res.qrPayload,
    }
    codeVisible.value = true
    ElMessage.success('借阅申请成功，请到馆取书')
    await Promise.all([loadDetail(), loadQuota()])
  } catch {
    // 拦截器已提示
  } finally {
    borrowing.value = false
  }
}

const openEdit = () => {
  editVisible.value = true
}

const formatTime = (t) => {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

const stockPercent = (b) => {
  if (!b?.totalCount) return 0
  return Math.round((b.availableCount / b.totalCount) * 100)
}

const stockClass = (b) => {
  const p = stockPercent(b)
  if (p === 0) return 'danger'
  if (p < 30) return 'warning'
  return ''
}

watch(() => route.params.id, (id) => {
  if (id && route.name === 'BookDetail') loadDetail()
})

onMounted(async () => {
  const catReq = bookApi.categoryTree().then((d) => { categories.value = d }).catch(() => {})
  await Promise.all([loadDetail(), loadQuota(), catReq])
})
</script>

<style scoped>
/* 加载 / 空态 */
.detail-loading,
.detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 96px 0;
  color: var(--color-text-tertiary);
  font-size: var(--fs-small);
}

.detail-loading .el-icon { font-size: 22px; color: var(--color-primary); }

.empty-char {
  font-family: var(--font-serif);
  font-size: 56px;
  color: var(--color-border-strong);
  line-height: 1;
}

/* 主体两栏 */
.detail-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--space-6);
  align-items: start;
}

/* 藏书票卡 */
.plate {
  position: sticky;
  top: calc(var(--header-height) + var(--space-4));
}

.plate-card {
  background: var(--color-bg-elevated, #fff);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-4);
  box-shadow: var(--shadow-sm);
  position: relative;
}

/* 藏书票装饰角线 */
.plate-card::before {
  content: '';
  position: absolute;
  top: 10px;
  left: 10px;
  width: 18px;
  height: 18px;
  border-top: 1px solid var(--color-border-strong);
  border-left: 1px solid var(--color-border-strong);
  border-top-left-radius: 6px;
  pointer-events: none;
}

.plate-card::after {
  content: '';
  position: absolute;
  bottom: 10px;
  right: 10px;
  width: 18px;
  height: 18px;
  border-bottom: 1px solid var(--color-border-strong);
  border-right: 1px solid var(--color-border-strong);
  border-bottom-right-radius: 6px;
  pointer-events: none;
}

.plate-cover {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--color-bg-elevated, #fff);
  box-shadow: 0 10px 26px -14px rgba(31, 27, 22, 0.35);
}

.plate-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.plate-cover.no-img {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, var(--color-bg-subtle), var(--color-bg-muted));
}

.plate-char {
  font-family: var(--font-serif);
  font-size: 84px;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
  opacity: 0.5;
}

.plate-price {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin: var(--space-4) 0 var(--space-2);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-divider);
}

.price-cny {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  color: var(--color-primary);
}

.price-num {
  font-family: var(--font-serif);
  font-size: 32px;
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  color: var(--color-text);
  line-height: 1;
}

.price-unit {
  margin-left: auto;
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
}

.plate-stock { margin-bottom: var(--space-4); }

.stock-line {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.stock-line .mono { font-family: var(--font-mono); }

.plate-actions { text-align: center; }

.act-btn { width: 100%; }

.act-tip {
  margin: 8px 0 0;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* 目录档案 */
.catalog { min-width: 0; }

.catalog-head { margin-bottom: var(--space-5); }

.catalog-title {
  font-family: var(--font-serif);
  font-size: var(--fs-h1);
  font-weight: 600;
  letter-spacing: var(--ls-tight);
  line-height: 1.25;
  color: var(--color-text);
  margin: 8px 0 10px;
}

.catalog-author {
  font-size: var(--fs-body);
  color: var(--color-text-secondary);
  margin: 0 0 var(--space-3);
}

.catalog-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
  background: var(--color-bg-subtle);
}

.chip.mono-chip { font-family: var(--font-mono); }

.chip-ok {
  border-color: color-mix(in srgb, var(--color-primary) 35%, transparent);
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.chip-off {
  border-color: color-mix(in srgb, var(--color-danger, #c0392b) 30%, transparent);
  color: var(--color-danger, #c0392b);
  background: color-mix(in srgb, var(--color-danger, #c0392b) 8%, transparent);
}

.catalog-section { margin-bottom: var(--space-5); }

.section-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: var(--fs-caption);
  font-weight: 500;
  letter-spacing: var(--ls-wide);
  color: var(--color-text-tertiary);
  margin-bottom: var(--space-3);
}

.section-label i {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--color-border-strong);
}

.catalog-desc {
  margin: 0;
  font-size: var(--fs-body);
  line-height: 1.9;
  color: var(--color-text-secondary);
  max-width: 62ch;
}

/* 档案规格表 */
.specs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 0;
  border-top: 1px solid var(--color-divider);
}

.spec {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 12px 4px;
  border-bottom: 1px solid var(--color-divider);
}

.spec.wide { grid-column: span 2; }

.spec dt {
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.spec dd {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--color-text);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spec dd.mono { font-family: var(--font-mono); font-size: var(--fs-caption); }
.spec dd.ok { color: var(--color-primary); font-weight: 500; }
.spec dd.off { color: var(--color-danger, #c0392b); }

/* 入场动画节奏 */
.fade-in { animation: rise var(--duration-slow) var(--ease-out) both; }
.fade-in.d1 { animation-delay: 60ms; }
.fade-in.d2 { animation-delay: 140ms; }
.fade-in.d3 { animation-delay: 220ms; }

@keyframes rise {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 900px) {
  .detail-grid { grid-template-columns: 1fr; }
  .plate { position: static; }
  .plate-card { max-width: 340px; margin: 0 auto; }
}
</style>
