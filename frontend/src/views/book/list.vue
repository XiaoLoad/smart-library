<template>
  <div class="page book-list-page" ref="pageRef">
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
            <span class="tree-tools">
              <el-tooltip content="展开全部分类" placement="top" effect="dark" :show-after="300">
                <button type="button" class="tree-tool" @click="setAllTreeExpanded(true)">
                  <el-icon><Expand /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="收起全部分类" placement="top" effect="dark" :show-after="300">
                <button type="button" class="tree-tool" @click="setAllTreeExpanded(false)">
                  <el-icon><Fold /></el-icon>
                </button>
              </el-tooltip>
            </span>
          </div>
          <el-input
            v-model="categoryFilter"
            placeholder="搜索分类"
            :prefix-icon="Search"
            clearable
            size="small"
            class="category-filter"
          />
          <div class="tree-wrap">
            <div class="tree-scroll" ref="treeScrollRef" @scroll="updateTreeScroll">
              <el-tree
                ref="cateTreeRef"
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
            </div>
            <div v-if="treeScrollable" class="tree-fade"></div>
          </div>
          <div class="all-books" :class="{ active: !selectedCategory }" @click="clearCategory">
            <el-icon><Notebook /></el-icon>
            <span>全部图书</span>
            <span class="count">{{ total }}</span>
          </div>
        </div>
      </aside>

      <!-- 图书列表 -->
      <section class="book-main fade-in">
        <div class="data-table">
          <!-- AI 检索结果横幅：AI 助手 queryBooks 结果联动展示 -->
          <div v-if="aiResult" class="smart-banner">
            <span class="smart-badge">
              <el-icon><MagicStick /></el-icon>
              AI 检索结果
            </span>
            <span class="smart-tags">「{{ aiResult.query }}」 · {{ aiResult.total }} 本（库存以下单时为准）</span>
            <el-button text size="small" class="smart-exit" @click="clearAiResult"
              >返回普通检索</el-button
            >
          </div>
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
              <el-tooltip
                content="试试对右下角的 AI 助手说「帮我找几本科幻小说」，检索结果会同步到这里"
                placement="bottom"
                effect="dark"
                :show-after="300"
              >
                <button type="button" class="ai-guide-btn" @click="openAiForSearch">
                  <el-icon><ChatDotRound /></el-icon>
                  让 AI 帮我找书
                </button>
              </el-tooltip>
            </div>
            <div class="right">
              <span v-if="!userStore.isAdmin && quota" class="quota-tip">
                剩余可借 {{ quota.remaining }} 本
              </span>
              <el-button
                v-if="userStore.isAdmin"
                type="primary"
                :icon="Plus"
                @click="openBookDialog('add')"
                >新增图书</el-button
              >
            </div>
          </div>

          <el-table
            :data="books"
            style="width: 100%"
            max-height="calc(100vh - 290px)"
          >
            <el-table-column label="书名" min-width="250">
              <template #default="{ row }">
                <div class="book-cell">
                  <div class="book-cover">
                    <img
                      v-if="row.coverUrl"
                      :src="row.coverUrl"
                      :alt="row.title"
                    />
                    <span v-else class="cover-char">{{
                      row.title.charAt(0)
                    }}</span>
                  </div>
                  <div class="book-info">
                    <el-tooltip
                      :content="row.title"
                      placement="top"
                      effect="dark"
                      :show-after="300"
                      :disabled="!row._titleTip"
                    >
                      <div
                        class="book-title"
                        @mouseenter="checkEllipsis($event, row, '_titleTip')"
                      >{{ row.title }}</div>
                    </el-tooltip>
                    <el-tooltip
                      :content="row.isbn"
                      placement="top"
                      effect="dark"
                      :show-after="300"
                      :disabled="!row._isbnTip"
                    >
                      <div
                        class="book-isbn"
                        @mouseenter="checkEllipsis($event, row, '_isbnTip')"
                      >{{ row.isbn }}</div>
                    </el-tooltip>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="author" label="作者" width="130" show-overflow-tooltip />
            <el-table-column prop="publisher" label="出版社" width="150" show-overflow-tooltip />
            <el-table-column label="馆藏位置" width="115">
              <template #default="{ row }">
                <el-tooltip
                  :content="row.location"
                  placement="top"
                  effect="dark"
                  :show-after="300"
                  :disabled="!row._locTip"
                >
                  <span
                    class="mono-text"
                    @mouseenter="checkEllipsis($event, row, '_locTip')"
                  >{{ row.location }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="库存" width="150">
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
            <el-table-column label="操作" width="160" fixed="right" class-name="ops-col">
              <template #default="{ row }">
                <el-button
                  text
                  type="primary"
                  size="small"
                  @click="openDetail(row)"
                  >详情</el-button
                >
                <el-button
                  v-if="userStore.isAdmin"
                  text
                  type="primary"
                  size="small"
                  @click="openBookDialog('edit', row)"
                  >编辑</el-button
                >
                <el-button
                  v-if="!userStore.isAdmin"
                  text
                  type="primary"
                  size="small"
                  :disabled="row.availableCount === 0 || !quota?.canBorrow"
                  :loading="borrowingId === row.id"
                  @click="onBorrow(row)"
                  >借阅</el-button
                >
              </template>
            </el-table-column>
            <template #empty>
              <div class="book-empty">
                <span class="empty-mark">∅</span>
                <p class="empty-title">
                  {{ selectedCategory ? '该分类下暂无图书' : '未找到匹配的图书' }}
                </p>
                <span class="empty-tip">
                  {{ selectedCategory ? '换个分类看看，或清除筛选浏览全部馆藏' : '试试更换关键词，或清除搜索条件' }}
                </span>
                <el-button
                  v-if="selectedCategory || search"
                  type="primary"
                  plain
                  size="small"
                  class="empty-clear"
                  @click="clearAllFilter"
                >清除筛选条件</el-button>
              </div>
            </template>
          </el-table>

          <div class="table-pagination" v-if="!aiResult">
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

    <!-- 图书新增/编辑弹窗（通用组件） -->
    <BookFormDialog
      v-model="bookDialogVisible"
      :mode="bookDialogMode"
      :book="editingBook"
      :categories="enabledCategories"
      @success="loadBooks"
    />

    <!-- 图书详情改为独立页面：/book/detail/:id -->
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from "vue";
import { useRouter } from "vue-router";
import { Search, Plus, Collection, Notebook, Expand, Fold, MagicStick, ChatDotRound } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { bookApi, borrowApi } from "@/api";
import { useUserStore } from "@/stores/user";
import { useAiSearchStore } from "@/stores/aiSearch";
import BorrowCodeDialog from "@/components/BorrowCodeDialog.vue";
import BookFormDialog from "@/components/BookFormDialog.vue";
import { useScrollRestore } from "@/composables/useScrollRestore";
import { useSnapshotRefresh } from "@/composables/useSnapshotRefresh";

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: "BookList" });

const pageRef = ref(null);
useScrollRestore(pageRef);

const userStore = useUserStore();
const books = ref([]);
const total = ref(0);
const page = ref(1);
const size = ref(10);
const search = ref("");
const categories = ref([]);
const categoryFilter = ref("");
const selectedCategory = ref(null);

// 借阅额度
const quota = ref(null);
const borrowingId = ref(null);
const codeVisible = ref(false);
const codeData = ref({
  borrowNo: "",
  pickupDeadline: "",
  bookTitle: "",
  qrPayload: "",
});

// 图书新增/编辑弹窗（通用组件状态）
const bookDialogVisible = ref(false);
const bookDialogMode = ref("add"); // add | edit
const editingBook = ref(null);

const openBookDialog = (mode, row = null) => {
  bookDialogMode.value = mode;
  editingBook.value = row;
  bookDialogVisible.value = true;
};

// 路由跳转（详情页）
const router = useRouter();

// 分类树滚动状态：超出可视高度时显示底部渐隐提示
const treeScrollRef = ref(null);
const treeScrollable = ref(false);
const updateTreeScroll = () => {
  const el = treeScrollRef.value;
  if (!el) return;
  treeScrollable.value = el.scrollHeight - el.scrollTop - el.clientHeight > 4;
};
watch(categories, () => nextTick(updateTreeScroll));

// el-tree-select 需要带 children 字段的数据
const categoryTreeData = computed(() => categories.value);

// 分类树一键展开 / 收起
const cateTreeRef = ref(null);
const setAllTreeExpanded = (expanded) => {
  const tree = cateTreeRef.value;
  if (!tree?.store?.nodesMap) return;
  Object.values(tree.store.nodesMap).forEach((node) => {
    node.expanded = expanded;
  });
};

// 图书表单可选分类：自身及祖先链全部启用（禁用分类不可挂载新图书）
const enabledCategories = computed(() => {
  const walk = (nodes) =>
    nodes
      .filter((n) => n.status === 1)
      .map((n) => {
        const children = n.children ? walk(n.children) : [];
        return children.length ? { ...n, children } : { ...n, children: undefined };
      });
  return walk(categories.value);
});

const filteredCategories = computed(() => {
  if (!categoryFilter.value) return categories.value;
  const filter = (nodes) => {
    return nodes
      .map((n) => {
        const children = n.children ? filter(n.children) : [];
        const match = n.categoryName.includes(categoryFilter.value);
        if (match || children.length) {
          return { ...n, children };
        }
        return null;
      })
      .filter(Boolean);
  };
  return filter(categories.value);
});

// 快照覆盖列表、分类树与借阅额度：三者都可能在别处被改动（分类页增删、详情页借阅）
const fetchSnapshot = async () => {
  const [data, tree, q] = await Promise.all([
    bookApi.list({
      page: page.value,
      size: size.value,
      keyword: search.value,
      categoryId: selectedCategory.value,
    }),
    bookApi.categoryTree(),
    // 额度取失败时回落为当前值，避免一次抖动把展示的额度抹成 null、顺带禁用借阅按钮
    userStore.isAdmin ? null : borrowApi.quota().catch(() => quota.value),
  ]);
  return { list: data.list, total: data.total, tree, quota: q };
};

const applySnapshot = (s) => {
  books.value = s.list;
  total.value = s.total;
  categories.value = s.tree;
  quota.value = s.quota;
};

const { reload: loadBooks } = useSnapshotRefresh({
  fetch: fetchSnapshot,
  apply: applySnapshot,
});

// ---------- AI 检索联动 ----------
// AI 助手中 queryBooks 工具的返回结果会写入 aiSearchStore；
// 本页 watch 到新结果后切换为「AI 检索结果」视图（横幅 + 列表覆盖）
const aiSearchStore = useAiSearchStore();
const aiResult = computed(() => aiSearchStore.result);

watch(aiResult, (val) => {
  if (!val) return;
  // 用 AI 返回的图书列表覆盖当前表格数据
  books.value = val.books;
  total.value = val.total;
  page.value = 1;
});

const clearAiResult = () => {
  aiSearchStore.clear();
  page.value = 1;
  loadBooks();
};

// 引导入口：唤起 AI 悬浮球并预填检索话术
const openAiForSearch = () => {
  aiSearchStore.requestOpen("帮我找几本好看的图书，我想看看馆藏有什么推荐");
};

const onSizeChange = () => {
  page.value = 1;
  loadBooks();
};

const onBorrow = async (row) => {
  if (userStore.isAdmin) return;
  try {
    await ElMessageBox.confirm(
      `确认借阅《${row.title}》？\n借阅成功后请及时到馆取书。`,
      "借阅确认",
      {
        type: "warning",
        confirmButtonText: "确认借阅",
        cancelButtonText: "取消",
      },
    );
  } catch {
    return; // 用户取消
  }
  borrowingId.value = row.id;
  try {
    const res = await borrowApi.apply({ bookId: row.id });
    codeData.value = {
      borrowNo: res.borrowNo,
      pickupDeadline: res.pickupDeadline,
      bookTitle: row.title,
      qrPayload: res.qrPayload,
    };
    codeVisible.value = true;
    ElMessage.success("借阅申请成功，请到馆取书");
    await loadBooks();
  } catch {
    // 错误已由拦截器提示
  } finally {
    borrowingId.value = null;
  }
};

// 图书详情：跳转独立详情页
const openDetail = (row) => {
  router.push(`/book/detail/${row.id}`);
};

const onCategoryClick = (node) => {
  selectedCategory.value = node.id;
  page.value = 1;
  loadBooks();
};

const clearCategory = () => {
  selectedCategory.value = null;
  page.value = 1;
  loadBooks();
};

// 空态：一键清除分类 + 关键词筛选
const clearAllFilter = () => {
  selectedCategory.value = null;
  search.value = "";
  page.value = 1;
  loadBooks();
};

const stockPercent = (row) => {
  if (!row.totalCount) return 0;
  return Math.round((row.availableCount / row.totalCount) * 100);
};

const stockClass = (row) => {
  const p = stockPercent(row);
  if (p === 0) return "danger";
  if (p < 30) return "warning";
  return "";
};

// 悬停时检测文本是否被截断（溢出），仅截断时启用 tooltip
const checkEllipsis = (e, row, flagKey) => {
  const el = e.currentTarget;
  row[flagKey] = el.scrollWidth > el.clientWidth + 1;
};

onMounted(async () => {
  await loadBooks();
  // 若 AI 检索结果在进入本页之前已产生（如在 AI 助手页聊完再切过来），直接应用
  if (aiSearchStore.result) {
    books.value = aiSearchStore.result.books;
    total.value = aiSearchStore.result.total;
    page.value = 1;
  }
  nextTick(updateTreeScroll);
});
</script>

<style scoped>
/* AI 智能检索提示条 */
.smart-banner {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 10px 14px;
  margin-bottom: var(--space-3);
  border-radius: var(--radius-md);
  background: linear-gradient(
    135deg,
    var(--color-primary-light),
    var(--color-secondary-light)
  );
  border: 1px solid var(--color-divider);
}

.smart-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-small);
  font-weight: 600;
  color: var(--color-primary);
  white-space: nowrap;
}

.smart-tags {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  min-width: 0;
  overflow: hidden;
}

.smart-tag {
  flex-shrink: 0;
}

.smart-exit {
  margin-left: auto;
  flex-shrink: 0;
}

/* AI 引导入口按钮 */
.ai-guide-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  height: 32px;
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-primary);
  font-size: var(--fs-small);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--duration-fast) var(--ease-soft);
}

.ai-guide-btn:hover {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
}

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

/* 分类树展开/收起工具 */
.tree-tools {
  margin-left: auto;
  display: inline-flex;
  gap: 4px;
}
.tree-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.18s ease;
}
.tree-tool:hover {
  color: var(--color-primary);
  border-color: var(--color-divider);
  background: var(--color-bg-soft, rgba(0, 0, 0, 0.02));
}

.category-filter {
  margin-bottom: var(--space-3);
}

/* 树滚动容器：限高 + 细滚动条 + 底部渐隐，防止长分类树撑开页面 */
.tree-wrap {
  position: relative;
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-divider);
}

.tree-scroll {
  max-height: 46vh;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border-strong) transparent;
  padding-right: 4px;
  margin-right: -4px;
}

.tree-scroll::-webkit-scrollbar {
  width: 4px;
}

.tree-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.tree-scroll::-webkit-scrollbar-thumb {
  background: var(--color-border-strong);
  border-radius: 999px;
}

.tree-fade {
  position: absolute;
  left: -4px;
  right: 0;
  bottom: calc(var(--space-3) + 1px);
  height: 40px;
  background: linear-gradient(
    to bottom,
    transparent,
    var(--color-bg-elevated, #fff)
  );
  pointer-events: none;
  border-radius: 0 0 var(--radius-md) var(--radius-md);
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

/* 图书表格（放大图书显示） */
:deep(.el-table th.el-table__cell),
:deep(.el-table td.el-table__cell) {
  padding: 14px 0;
}

/* 表格内部滚动：细化滚动条 */
:deep(.el-table .el-scrollbar__bar.is-vertical) {
  width: 6px;
  right: 2px;
}

:deep(.el-table .el-scrollbar__bar.is-horizontal) {
  height: 6px;
  bottom: 2px;
}

:deep(.el-table .el-scrollbar__bar .el-scrollbar__thumb) {
  border-radius: 999px;
}

/* 操作列按钮禁止换行挤压 */
:deep(.el-table .ops-col .cell) {
  white-space: nowrap;
}

.book-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.book-cover {
  width: 52px;
  height: 68px;
  border-radius: var(--radius-md);
  background: linear-gradient(
    135deg,
    var(--color-primary-light),
    var(--color-secondary-light)
  );
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  box-shadow: 0 6px 16px -10px rgba(31, 27, 22, 0.4);
}

.cover-char {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 600;
  color: var(--color-primary);
}

/* 有真实封面时铺满容器 */
.book-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: inherit;
  display: block;
}

.book-info {
  min-width: 0;
  flex: 1;
}
.book-info .book-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 1.4;
  color: var(--color-text);
  margin-bottom: 3px;
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
  .book-layout {
    grid-template-columns: 1fr;
  }
  .category-card {
    position: static;
  }
}

.quota-tip {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  margin-right: var(--space-2);
}

/* 图书空态 */
.book-empty {
  padding: 56px 0 60px;
  text-align: center;
}
.book-empty .empty-mark {
  display: block;
  font-size: 38px;
  color: var(--color-text-tertiary);
  margin-bottom: 10px;
}
.book-empty .empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 6px;
}
.book-empty .empty-tip {
  display: block;
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
  margin-bottom: 14px;
}
.book-empty .empty-clear {
  margin: 0 auto;
}
</style>
