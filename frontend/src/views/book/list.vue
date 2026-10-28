<template>
  <div class="page collection-page" ref="pageRef">
    <!-- 刊头 -->
    <header class="masthead">
      <div class="masthead-copy">
        <span class="eyebrow">馆藏 · COLLECTION</span>
        <h1>馆藏图书</h1>
        <p class="desc">在 {{ displayTotal }} 册馆藏里，慢慢挑一本。</p>
      </div>
      <div class="masthead-side">
        <div class="ledger">
          <span class="ledger-num">{{ displayTotal }}</span>
          <span class="ledger-label">CATALOGUED · 册</span>
        </div>
        <span class="masthead-rule" aria-hidden="true"></span>
        <div class="masthead-act">
          <template v-if="userStore.isAdmin">
            <button type="button" class="ghost-btn" @click="openBookDialog('add')">
              <el-icon><Plus /></el-icon>新增图书
            </button>
          </template>
          <template v-else-if="quota">
            <span class="act-label">剩余可借</span>
            <span class="act-num">{{ quota.remaining }}<i>本</i></span>
          </template>
        </div>
      </div>
    </header>

    <!-- 检索带：滚动到下方时吸顶 -->
    <section ref="bandRef" class="search-band" :class="{ 'has-result': !!aiResult }">
      <div class="band-row">
        <div class="band-label">
          <span class="band-eyebrow">AI SEARCH</span>
          <span class="band-title">检索</span>
        </div>

        <div class="band-input">
          <el-icon class="band-icon"><Search /></el-icon>
          <input
            v-model="search"
            type="text"
            placeholder="书名 / 作者 / ISBN，或直接描述你想读的"
            aria-label="检索馆藏"
            @keyup.enter="runSearch"
          />
          <button v-if="search" type="button" class="band-clear" aria-label="清空关键词" @click="clearKeyword">×</button>
          <button type="button" class="band-go" @click="runSearch">检索</button>
          <span class="band-sep" aria-hidden="true"></span>
          <button type="button" class="band-ai" @click="openAiForSearch">
            <el-icon><MagicStick /></el-icon>AI 帮我找
          </button>
        </div>

        <div class="band-right">
          <span class="band-count">共 <b>{{ total }}</b> 册</span>
          <button
            v-if="showTopBtn"
            type="button"
            class="top-btn"
            aria-label="回到顶部"
            @click="scrollToTop"
          ><el-icon><Top /></el-icon></button>
        </div>
      </div>

      <div class="band-sub" v-if="!aiResult">
        <span class="sub-label">试试</span>
        <button
          v-for="sample in aiSamples"
          :key="sample.label"
          type="button"
          class="sample-link"
          @click="askAi(sample.text)"
        >{{ sample.label }}</button>
        <span class="sub-hint">点击后由 AI 助手检索，结果回填本页</span>
      </div>
      <div class="band-sub is-result" v-else>
        <span class="result-query">「{{ aiResult.query }}」</span>
        <span class="result-hit">命中 <b>{{ aiResult.total }}</b> 本</span>
        <span class="sub-hint">库存以下单时为准</span>
        <button type="button" class="result-exit" @click="clearAiResult">返回普通检索</button>
      </div>
    </section>

    <!-- 目次 + 书墙 -->
    <div class="collection-body">
      <aside class="index-rail">
        <div class="rail-head">
          <div>
            <span class="rail-eyebrow">INDEX</span>
            <span class="rail-title">分类目次</span>
          </div>
          <span class="rail-tools">
            <el-tooltip content="展开全部分类" placement="right" effect="dark" :show-after="300">
              <button type="button" class="rail-tool" @click="setAllTreeExpanded(true)">
                <el-icon><Expand /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="收起全部分类" placement="right" effect="dark" :show-after="300">
              <button type="button" class="rail-tool" @click="setAllTreeExpanded(false)">
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
          class="rail-filter"
        />

        <div class="rail-tree-wrap">
          <div class="rail-scroll" ref="treeScrollRef" @scroll="updateTreeScroll">
            <el-tree
              ref="cateTreeRef"
              :data="filteredCategories"
              node-key="id"
              :props="{ label: 'categoryName', children: 'children' }"
              :expand-on-click-node="false"
              default-expand-all
              @node-click="onCategoryClick"
              class="rail-tree"
            >
              <template #default="{ data }">
                <span class="rail-node" :class="{ active: data.id === selectedCategory }">
                  <span class="rail-node-name">{{ data.categoryName }}</span>
                  <span class="rail-node-code">{{ data.categoryCode }}</span>
                </span>
              </template>
            </el-tree>
          </div>
          <div v-if="treeScrollable" class="rail-fade"></div>
        </div>

        <div class="rail-all" :class="{ active: !selectedCategory }" @click="clearCategory">
          <el-icon><Notebook /></el-icon>
          <span>全馆藏书</span>
          <span class="rail-all-count">{{ total }}</span>
        </div>
      </aside>

      <main class="shelf-area" ref="stageRef" @pointermove="onStageMove" @pointerleave="onStageLeave">
        <div class="shelf-bar" v-if="selectedCategory">
          <span class="shelf-bar-label">分类筛选</span>
          <span class="filter-tag">
            {{ selectedCategoryName }}
            <button type="button" aria-label="清除分类" @click="clearCategory">×</button>
          </span>
          <button type="button" class="filter-reset" @click="clearAllFilter">清除筛选</button>
        </div>

        <div v-if="books.length" ref="gridRef" class="wall">
          <article v-for="(row, i) in books" :key="row.id" class="volume" :data-book-id="row.id">
            <div class="volume-cover" @click="openDetail(row, $event)">
              <span class="volume-no">{{ volumeNo(i) }}</span>
              <img
                v-if="row.coverUrl && !brokenCovers.has(row.id)"
                :src="row.coverUrl"
                :alt="row.title"
                loading="lazy"
                @error="brokenCovers.add(row.id)"
              />
              <span v-else class="cover-fallback">
                <span class="fallback-char">{{ row.title.charAt(0) }}</span>
                <span class="fallback-en">{{ row.author || 'NO COVER' }}</span>
              </span>
              <span class="cover-spine"></span>
              <span class="cover-avail" :class="stockClass(row)">
                {{ row.availableCount === 0 ? '已借完' : `可借 ${row.availableCount}` }}
              </span>
              <div class="cover-actions">
                <button type="button" class="cover-btn" @click.stop="openDetail(row, $event)">详情</button>
                <button
                  v-if="userStore.isAdmin"
                  type="button"
                  class="cover-btn"
                  @click.stop="openBookDialog('edit', row)"
                >编辑</button>
                <button
                  v-else
                  type="button"
                  class="cover-btn is-primary"
                  :disabled="row.availableCount === 0 || !quota?.canBorrow"
                  @click.stop="onBorrow(row)"
                >{{ borrowingId === row.id ? '申请中…' : '借阅' }}</button>
              </div>
            </div>

            <div class="volume-meta">
              <h3 class="volume-title" :title="row.title" @click="openDetail(row, $event)">{{ row.title }}</h3>
              <p class="volume-sub" :title="`${row.author}${row.publisher ? ' · ' + row.publisher : ''}`">
                <span>{{ row.author }}</span>
                <span v-if="row.publisher" class="sub-dot">·</span>
                <span v-if="row.publisher" class="sub-pub">{{ row.publisher }}</span>
              </p>
              <div class="volume-foot">
                <span class="foot-loc" :title="row.location">{{ row.location || '未上架' }}</span>
                <span class="foot-stock">
                  <span class="stock-track"><i :class="stockClass(row)" :style="{ width: stockPercent(row) + '%' }"></i></span>
                  <em>{{ row.availableCount }}/{{ row.totalCount }}</em>
                </span>
              </div>
            </div>
          </article>
        </div>

        <div v-else class="wall-empty">
          <span class="empty-ghost">无</span>
          <p class="empty-title">{{ selectedCategory ? '该分类下暂无图书' : '未找到匹配的图书' }}</p>
          <span class="empty-tip">
            {{ selectedCategory ? '换个分类看看，或清除筛选浏览全部馆藏' : '试试更换关键词，或清除搜索条件' }}
          </span>
          <button v-if="selectedCategory || search" type="button" class="empty-btn" @click="clearAllFilter">
            清除筛选条件
          </button>
        </div>

        <!-- 手动加载下一批：页脚始终可达，不做自动滚动加载 -->
        <div class="wall-foot" v-if="books.length && !aiResult">
          <span class="wall-foot-note">已展示 {{ books.length }} / 共 {{ total }} 册</span>
          <button
            v-if="hasMore"
            type="button"
            class="load-btn"
            :disabled="loadingMore"
            @click="loadMore"
          >
            <span>{{ loadingMore ? '正在取书…' : `继续加载（剩 ${total - books.length} 册）` }}</span>
            <el-icon v-if="!loadingMore"><ArrowDown /></el-icon>
          </button>
          <span v-else class="wall-foot-end">已到馆藏末尾</span>
        </div>
      </main>
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

    <!-- 图书详情为独立页面：/book/detail/:id -->
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, onActivated, onBeforeUnmount, watch, nextTick } from "vue";
import { useRouter } from "vue-router";
import {
  Search, Plus, Notebook, Expand, Fold, ArrowDown, Top, MagicStick,
} from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { gsap } from "gsap";
import { bookApi, borrowApi } from "@/api";
import { useUserStore } from "@/stores/user";
import { useAiSearchStore } from "@/stores/aiSearch";
import { useBookTransitionStore } from "@/stores/bookTransition";
import BorrowCodeDialog from "@/components/BorrowCodeDialog.vue";
import BookFormDialog from "@/components/BookFormDialog.vue";
import { useScrollRestore } from "@/composables/useScrollRestore";
import { useSnapshotRefresh } from "@/composables/useSnapshotRefresh";

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: "BookList" });

const pageRef = ref(null);
const stageRef = ref(null);
const gridRef = ref(null);
const bandRef = ref(null);
useScrollRestore(pageRef);

const userStore = useUserStore();
const books = ref([]);
const total = ref(0);
const page = ref(1); // 已加载的批次页数（滚动加载，不再是分页跳转）
const size = 20; // 每批数量
const search = ref("");
const categories = ref([]);
const categoryFilter = ref("");
const selectedCategory = ref(null);
const brokenCovers = reactive(new Set());
const loadingMore = ref(false);
const showTopBtn = ref(false);

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

// 路由跳转（详情页）：先登记源封面位置，由全局覆盖层做抽书翻页转场
const router = useRouter();
const bookTransition = useBookTransitionStore();

const prefersReducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const openDetail = (row, evt) => {
  const coverEl = evt?.currentTarget?.closest?.(".volume")?.querySelector(".volume-cover");
  if (coverEl && window.innerWidth >= 768 && !prefersReducedMotion()) {
    const r = coverEl.getBoundingClientRect();
    bookTransition.begin({
      id: row.id,
      rect: { left: r.left, top: r.top, width: r.width, height: r.height },
      coverUrl: row.coverUrl && !brokenCovers.has(row.id) ? row.coverUrl : "",
      title: row.title,
      author: row.author || "",
      char: row.title?.charAt(0) || "书",
    });
  }
  router.push(`/book/detail/${row.id}`);
};

// 分类树滚动状态：超出可视高度时显示底部渐隐提示
const treeScrollRef = ref(null);
const treeScrollable = ref(false);
const updateTreeScroll = () => {
  const el = treeScrollRef.value;
  if (!el) return;
  treeScrollable.value = el.scrollHeight - el.scrollTop - el.clientHeight > 4;
};
watch(categories, () => nextTick(updateTreeScroll));

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

// 当前分类名（筛选条展示）
const selectedCategoryName = computed(() => {
  const walk = (nodes) => {
    for (const n of nodes) {
      if (n.id === selectedCategory.value) return n.categoryName;
      const hit = n.children ? walk(n.children) : "";
      if (hit) return hit;
    }
    return "";
  };
  return walk(categories.value);
});

// AI 检索示例：点击即唤起悬浮球并预填话术（结果回填本页）
const aiSamples = [
  { label: "科幻小说", text: "帮我找几本科幻小说" },
  { label: "刘慈欣的书", text: "馆里有哪些刘慈欣的书？" },
  { label: "最近热门", text: "最近借得最多的书有哪些？" },
  { label: "入门哲学", text: "推荐几本适合入门的哲学书" },
];

// 快照：始终取「第 1 页到当前已加载页」的累计区间，keep-alive 激活时可原样替换而不丢已加载的册数
const fetchSnapshot = async () => {
  const [data, tree, q] = await Promise.all([
    bookApi.list({
      page: 1,
      size: size * page.value,
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

const hasMore = computed(() => !aiResult.value && books.value.length < Number(total.value || 0));

/* 回到书墙顶部：检索带吸顶后，让用户立刻看到新结果的第一排 */
const scrollToShelf = () => {
  const el = stageRef.value;
  if (!el) return;
  // 顶部黏性元素会遮住第一排：扣掉全局 header 与吸顶检索带的高度
  const headerH = document.querySelector(".app-header")?.offsetHeight || 60;
  const bandH = bandRef.value?.offsetHeight || 0;
  const top = el.getBoundingClientRect().top + window.scrollY - headerH - bandH - 12;
  window.scrollTo({ top: Math.max(0, top), behavior: reduceMotion ? "auto" : "smooth" });
};

const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
};

/* 检索/筛选变化：回到第 1 批并滚回书墙顶部 */
const runSearch = async () => {
  page.value = 1;
  await loadBooks();
  scrollToShelf();
};

/* 继续加载下一批（滚动到底自动触发，也可点按钮） */
const loadMore = async () => {
  if (loadingMore.value || !hasMore.value || aiResult.value) return;
  loadingMore.value = true;
  const next = page.value + 1;
  const before = books.value.length;
  appending = true;
  try {
    const data = await bookApi.list({
      page: next,
      size,
      keyword: search.value,
      categoryId: selectedCategory.value,
    });
    const seen = new Set(books.value.map((b) => b.id));
    const added = (data.list || []).filter((b) => !seen.has(b.id));
    books.value = [...books.value, ...added];
    total.value = data.total;
    page.value = next;
    await nextTick();
    animateAppended(before, added.length);
  } catch {
    // 错误已由拦截器提示
  } finally {
    appending = false;
    loadingMore.value = false;
  }
};

// 滚动加载已改为手动按钮：页脚常驻可见，避免一口气把所有批次都拉完

// ---------- AI 检索联动 ----------
const aiSearchStore = useAiSearchStore();
const aiResult = computed(() => aiSearchStore.result);

watch(aiResult, (val) => {
  if (!val) return;
  books.value = val.books;
  total.value = val.total;
  page.value = 1;
  animateBandResult();
});

const clearAiResult = async () => {
  aiSearchStore.clear();
  await runSearch();
};

// AI 入口：优先使用输入框里已写下的检索意图
const openAiForSearch = () => {
  const intent = search.value.trim();
  aiSearchStore.requestOpen(intent || "帮我找几本好看的图书，我想看看馆藏有什么推荐");
};

const askAi = (text) => {
  aiSearchStore.requestOpen(text);
};

const clearKeyword = async () => {
  search.value = "";
  await runSearch();
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

const onCategoryClick = async (node) => {
  selectedCategory.value = node.id;
  await runSearch();
};

const clearCategory = async () => {
  selectedCategory.value = null;
  await runSearch();
};

// 空态：一键清除分类 + 关键词筛选
const clearAllFilter = async () => {
  selectedCategory.value = null;
  search.value = "";
  await runSearch();
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

// 书架序号（按当前已加载顺序连续）
const volumeNo = (index) => String(index + 1).padStart(2, "0");

// ---------- 动效（GSAP） ----------
const reduceMotion =
  typeof window !== "undefined" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let gsapCtx = null;
let totalTween = null;

// 馆藏总量数字滚动
const displayTotal = ref(0);
watch(
  total,
  (val) => {
    const target = Number(val) || 0;
    if (reduceMotion) {
      displayTotal.value = target;
      return;
    }
    const proxy = { v: displayTotal.value };
    totalTween?.kill();
    totalTween = gsap.to(proxy, {
      v: target,
      duration: 0.7,
      ease: "power2.out",
      onUpdate: () => {
        displayTotal.value = Math.round(proxy.v);
      },
    });
  },
  { immediate: true },
);

// 首屏编排：刊头 → 检索带 → 目次
const playIntro = () => {
  if (reduceMotion || !pageRef.value) return;
  gsapCtx = gsap.context(() => {
    const tl = gsap.timeline({ defaults: { ease: "power3.out" } });
    tl.from(".masthead .eyebrow", {
      autoAlpha: 0, y: 10, duration: 0.45, clearProps: "transform,opacity,visibility",
    })
      .from(".masthead h1", {
        autoAlpha: 0, y: 18, duration: 0.6, clearProps: "transform,opacity,visibility",
      }, "-=0.3")
      .from(".masthead .desc, .masthead-side", {
        autoAlpha: 0, y: 10, duration: 0.45, clearProps: "transform,opacity,visibility",
      }, "-=0.35")
      .from(".search-band", {
        autoAlpha: 0, y: 12, duration: 0.5, clearProps: "transform,opacity,visibility",
      }, "-=0.3")
      .from(".index-rail", {
        autoAlpha: 0, x: -14, duration: 0.55, clearProps: "transform,opacity,visibility",
      }, "-=0.35");
  }, pageRef.value);
};

const staggerIn = (els, delayStep = 0.045) => {
  gsap.fromTo(
    els,
    { autoAlpha: 0, y: 24 },
    {
      autoAlpha: 1,
      y: 0,
      duration: 0.55,
      ease: "power3.out",
      stagger: delayStep,
      overwrite: true,
      clearProps: "transform,opacity,visibility",
    },
  );
  const imgs = Array.from(els).map((el) => el.querySelector(".volume-cover img")).filter(Boolean);
  if (imgs.length) {
    gsap.fromTo(
      imgs,
      { scale: 1.08 },
      { scale: 1, duration: 1.1, ease: "power2.out", stagger: delayStep, clearProps: "transform" },
    );
  }
};

// 书墙整体变化（首屏 / 检索 / 筛选 / AI 结果）；追加加载时只动新增的那批
let appending = false;
const animateCards = () => {
  if (reduceMotion || !gridRef.value) return;
  const cards = gridRef.value.querySelectorAll(".volume");
  if (cards.length) staggerIn(cards);
};
watch(books, () => {
  if (appending) return;
  nextTick(animateCards);
});

// 只给新追加的一批做入场，不打扰已阅读的部分
const animateAppended = (start, count) => {
  if (reduceMotion || !gridRef.value || count <= 0) return;
  const cards = Array.from(gridRef.value.querySelectorAll(".volume")).slice(start, start + count);
  if (cards.length) staggerIn(cards, 0.04);
};

// AI 结果：检索带文案与数字轻盈浮现
const animateBandResult = async () => {
  await nextTick();
  const band = bandRef.value;
  if (!band || reduceMotion) return;
  const lines = band.querySelectorAll(".result-query, .result-hit, .result-exit");
  if (lines.length) {
    gsap.fromTo(
      lines,
      { autoAlpha: 0, y: 6 },
      { autoAlpha: 1, y: 0, duration: 0.45, stagger: 0.07, clearProps: "transform,opacity,visibility" },
    );
  }
};

// 封面随指针轻微立体偏转（事件委托，渲染多次也不重复绑定）
let tiltCard = null;

const resetTilt = (card) => {
  if (!card || reduceMotion) return;
  const cover = card.querySelector(".volume-cover");
  if (cover) gsap.to(cover, { rotateX: 0, rotateY: 0, y: 0, duration: 0.7, ease: "power3.out", overwrite: "auto" });
};

const onStageMove = (e) => {
  if (reduceMotion) return;
  const card = e.target?.closest?.(".volume") || null;
  if (tiltCard && tiltCard !== card) resetTilt(tiltCard);
  tiltCard = card;
  if (!card) return;
  const cover = card.querySelector(".volume-cover");
  if (!cover) return;
  const rect = cover.getBoundingClientRect();
  const clamp = (v) => Math.max(-0.5, Math.min(0.5, v));
  const px = clamp((e.clientX - rect.left) / rect.width - 0.5);
  const py = clamp((e.clientY - rect.top) / rect.height - 0.5);
  gsap.to(cover, {
    rotateY: px * 6,
    rotateX: -py * 6,
    y: -4,
    transformPerspective: 1000,
    duration: 0.45,
    ease: "power2.out",
    overwrite: "auto",
  });
};

const onStageLeave = () => {
  resetTilt(tiltCard);
  tiltCard = null;
};

const onWindowScroll = () => {
  showTopBtn.value = (window.scrollY || 0) > 360;
};

// 详情 → 列表：等滚动位置恢复后再量原卡片，交给转场层做「合书放回」
onActivated(async () => {
  if (!bookTransition.active || bookTransition.mode !== "return") return
  await nextTick();
  await new Promise((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );
  const id = bookTransition.payload?.id;
  if (id == null) return;
  const el = stageRef.value?.querySelector(`.volume[data-book-id="${id}"] .volume-cover`);
  if (el) bookTransition.registerTarget(el);
});

onMounted(async () => {
  playIntro();
  window.addEventListener("scroll", onWindowScroll, { passive: true });
  await loadBooks();
  // 若 AI 检索结果在进入本页之前已产生（如在 AI 助手页聊完再切过来），直接应用
  if (aiSearchStore.result) {
    books.value = aiSearchStore.result.books;
    total.value = aiSearchStore.result.total;
    page.value = 1;
  }
  nextTick(updateTreeScroll);
});

onBeforeUnmount(() => {
  gsapCtx?.revert();
  totalTween?.kill();
  gsap.killTweensOf(".volume-cover");
  window.removeEventListener("scroll", onWindowScroll);
});
</script>

<style scoped>
/* ========== 刊头 ========== */
.masthead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-5);
  padding-bottom: var(--space-4);
}

.masthead h1 {
  font-family: var(--font-serif);
  font-size: clamp(2.4rem, 3.2vw, 3.1rem);
  font-weight: 600;
  letter-spacing: -0.035em;
  line-height: 1.05;
  margin-bottom: var(--space-2);
}

.masthead .desc {
  color: var(--color-text-tertiary);
  font-size: var(--fs-small);
}

.masthead-side {
  display: flex;
  align-items: flex-end;
  gap: var(--space-4);
  flex-shrink: 0;
  padding-bottom: 4px;
}

.ledger {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.ledger-num {
  font-family: var(--font-serif);
  font-size: 2rem;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.ledger-label {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.22em;
  color: var(--color-text-tertiary);
}

.masthead-rule {
  width: 1px;
  height: 34px;
  background: var(--color-border);
}

.masthead-act {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--fs-small);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.ghost-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background: var(--color-primary-light);
}

.act-label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--color-text-tertiary);
}

.act-num {
  font-family: var(--font-serif);
  font-size: 1.3rem;
  line-height: 1;
  color: var(--color-primary);
}

.act-num i {
  font-style: normal;
  font-family: var(--font-sans);
  font-size: 10px;
  color: var(--color-text-tertiary);
  margin-left: 3px;
}

/* ========== 吸顶检索带 ========== */
.search-band {
  position: sticky;
  /* 让开全局顶栏：顶栏 60px 且同为 sticky，这里从顶栏下沿开始吸 */
  top: var(--header-height);
  z-index: 9;
  margin: var(--space-4) calc(-1 * var(--space-6)) 0;
  padding: var(--space-3) var(--space-6);
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-border);
  transition: background var(--duration) var(--ease-soft);
}

.search-band.has-result {
  background: linear-gradient(180deg, var(--color-primary-light), var(--color-bg) 85%);
  border-bottom-color: rgba(58, 90, 107, 0.28);
}

.band-row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  min-height: 40px;
}

.band-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-shrink: 0;
  min-width: 108px;
}

.band-eyebrow {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: var(--color-text-tertiary);
}

.band-title {
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  color: var(--color-text);
}

.band-input {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 40px;
  padding: 0 10px 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-elevated);
  transition: border-color var(--duration-fast) var(--ease-soft),
    box-shadow var(--duration-fast) var(--ease-soft);
}

.band-input:focus-within {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-light);
}

.band-icon {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.band-input input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  color: var(--color-text);
}

.band-input input::placeholder {
  color: var(--color-text-tertiary);
}

.band-clear {
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: var(--color-bg-subtle);
  color: var(--color-text-tertiary);
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
}

.band-clear:hover {
  color: var(--color-text);
}

.band-go {
  flex-shrink: 0;
  height: 28px;
  padding: 0 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  color: var(--color-text-secondary);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.band-go:hover {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.band-sep {
  width: 1px;
  height: 16px;
  background: var(--color-border);
  flex-shrink: 0;
}

.band-ai {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  height: 28px;
  padding: 0 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-primary);
  font-size: var(--fs-caption);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-soft);
}

.band-ai:hover {
  background: var(--color-primary-light);
}

.band-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-shrink: 0;
}

.band-count {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.band-count b {
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  color: var(--color-text);
}

.top-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.top-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

/* 检索带第二行 */
.band-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: 6px;
  padding-left: 124px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.sub-label {
  font-family: var(--font-mono);
  letter-spacing: 0.16em;
}

.sample-link {
  border: none;
  border-bottom: 1px dotted var(--color-border-strong);
  padding: 0 0 1px;
  background: none;
  color: var(--color-text-secondary);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-soft),
    border-color var(--duration-fast) var(--ease-soft);
}

.sample-link:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.sub-hint {
  margin-left: auto;
}

.band-sub.is-result {
  color: var(--color-primary);
}

.result-query {
  font-family: var(--font-serif);
  font-size: var(--fs-small);
  color: var(--color-text);
}

.result-hit b {
  font-family: var(--font-serif);
  color: var(--color-primary);
}

.band-sub.is-result .sub-hint {
  color: var(--color-text-tertiary);
}

.result-exit {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.result-exit:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

/* ========== 目次 + 书墙 ========== */
.collection-body {
  display: grid;
  grid-template-columns: 206px minmax(0, 1fr);
  gap: var(--space-6);
  margin-top: var(--space-5);
}

/* 左侧目次：书页边注，跟随页面滚动吸顶 */
.index-rail {
  min-width: 0;
  padding-right: var(--space-5);
  border-right: 1px solid var(--color-border);
  position: sticky;
  /* 顶栏 + 吸顶检索带（约 92px）之下 */
  top: calc(var(--header-height) + 96px);
  align-self: start;
  max-height: calc(100vh - var(--header-height) - 116px);
  display: flex;
  flex-direction: column;
}

.rail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.rail-eyebrow {
  display: block;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.2em;
  color: var(--color-text-tertiary);
}

.rail-title {
  display: block;
  margin-top: 2px;
  font-family: var(--font-serif);
  font-size: var(--fs-body);
  letter-spacing: 0.04em;
  color: var(--color-text);
}

.rail-tools {
  display: inline-flex;
  gap: 2px;
  flex-shrink: 0;
}

.rail-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.rail-tool:hover {
  color: var(--color-primary);
  background: var(--color-bg-subtle);
}

.rail-filter {
  margin-bottom: var(--space-2);
}

.rail-tree-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
}

.rail-scroll {
  max-height: calc(100vh - var(--header-height) - 330px);
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border-strong) transparent;
  padding-right: 4px;
  margin-right: -4px;
}

.rail-scroll::-webkit-scrollbar {
  width: 4px;
}

.rail-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.rail-scroll::-webkit-scrollbar-thumb {
  background: var(--color-border-strong);
  border-radius: 999px;
}

.rail-fade {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 32px;
  background: linear-gradient(to bottom, transparent, var(--color-bg));
  pointer-events: none;
}

.rail-node {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
  padding-right: var(--space-2);
}

.rail-node-name {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  transition: color var(--duration-fast) var(--ease-soft);
}

.rail-node-code {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.rail-node.active .rail-node-name {
  color: var(--color-primary);
  font-weight: 600;
}

.rail-node.active .rail-node-code {
  color: var(--color-primary);
}

.rail-tree {
  background: transparent;
}

.rail-tree :deep(.el-tree-node__content) {
  height: 32px;
  border-radius: var(--radius-sm);
  background: transparent;
}

.rail-tree :deep(.el-tree-node__content:hover) {
  background: var(--color-bg-subtle);
}

.rail-tree :deep(.el-tree-node__expand-icon) {
  color: var(--color-text-tertiary);
  font-size: 11px;
}

.rail-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: transparent;
}

.rail-all {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-soft);
}

.rail-all:hover,
.rail-all.active {
  color: var(--color-primary);
}

.rail-all-count {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

/* ========== 书墙 ========== */
.shelf-area {
  min-width: 0;
}

.shelf-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.shelf-bar-label {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
}

.filter-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 4px 2px 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  color: var(--color-text-secondary);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter-tag button {
  border: none;
  background: none;
  padding: 0 4px;
  color: var(--color-text-tertiary);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.filter-tag button:hover {
  color: var(--color-primary);
}

.filter-reset {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--color-text-tertiary);
  font-size: var(--fs-caption);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.filter-reset:hover {
  color: var(--color-primary);
}

.wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(198px, 1fr));
  gap: 32px 22px;
}

.volume {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.volume-cover {
  position: relative;
  aspect-ratio: 3 / 4;
  border-radius: 3px;
  overflow: hidden;
  background: linear-gradient(150deg, var(--color-primary-light), var(--color-secondary-light));
  border: 1px solid var(--color-border);
  box-shadow: 0 14px 26px -20px rgba(31, 27, 22, 0.55);
  cursor: pointer;
  transform-style: preserve-3d;
  transition: box-shadow var(--duration) var(--ease-soft),
    border-color var(--duration) var(--ease-soft);
}

.volume:hover .volume-cover {
  border-color: var(--color-border-strong);
  box-shadow: 0 22px 38px -22px rgba(31, 27, 22, 0.6);
}

.volume-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-spine {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 7px;
  background: linear-gradient(90deg, rgba(0, 0, 0, 0.24), rgba(0, 0, 0, 0));
  pointer-events: none;
}

.volume-no {
  position: absolute;
  top: 8px;
  left: 10px;
  z-index: 2;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.92);
  text-shadow: 0 1px 6px rgba(0, 0, 0, 0.5);
}

.cover-avail {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.88);
  font-size: 10px;
  color: var(--color-primary);
}

.cover-avail.warning { color: var(--color-warning); }
.cover-avail.danger { color: var(--color-danger); }

.cover-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 16px;
  text-align: center;
}

.fallback-char {
  font-family: var(--font-serif);
  font-size: 2.4rem;
  font-weight: 600;
  line-height: 1;
  color: var(--color-primary);
}

.fallback-en {
  max-width: 100%;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: var(--color-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cover-actions {
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 8px;
  z-index: 2;
  display: flex;
  gap: 6px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity var(--duration) var(--ease-soft),
    transform var(--duration) var(--ease-soft);
}

.volume:hover .cover-actions,
.volume-cover:focus-within .cover-actions {
  opacity: 1;
  transform: translateY(0);
}

@media (hover: none) {
  .cover-actions {
    opacity: 1;
    transform: none;
  }
}

.cover-btn {
  flex: 1;
  height: 30px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(6px);
  color: var(--color-text);
  font-size: var(--fs-caption);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.cover-btn:hover {
  background: var(--color-bg-elevated);
  color: var(--color-primary);
}

.cover-btn.is-primary {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-inverse);
}

.cover-btn.is-primary:hover {
  background: var(--color-primary-hover);
  color: var(--color-text-inverse);
}

.cover-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.volume-meta {
  min-width: 0;
}

.volume-title {
  margin: 0 0 3px;
  font-family: var(--font-serif);
  font-size: 0.9688rem;
  font-weight: 600;
  line-height: 1.45;
  color: var(--color-text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-soft);
}

.volume-title:hover {
  color: var(--color-primary);
}

.volume-sub {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 8px;
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  overflow: hidden;
  white-space: nowrap;
}

.volume-sub .sub-pub {
  overflow: hidden;
  text-overflow: ellipsis;
}

.sub-dot {
  opacity: 0.6;
  flex-shrink: 0;
}

.volume-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 7px;
  border-top: 1px solid var(--color-divider);
}

.foot-loc {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foot-stock {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.stock-track {
  display: inline-block;
  width: 34px;
  height: 3px;
  border-radius: 2px;
  background: var(--color-bg-muted);
  overflow: hidden;
}

.stock-track i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--color-secondary);
  transition: width var(--duration-slow) var(--ease-out);
}

.stock-track i.warning { background: var(--color-warning); }
.stock-track i.danger { background: var(--color-danger); }

.foot-stock em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-text-tertiary);
}

/* ========== 空态 ========== */
.wall-empty {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 380px;
  padding: var(--space-6);
  text-align: center;
  border-top: 1px solid var(--color-divider);
  border-bottom: 1px solid var(--color-divider);
  overflow: hidden;
}

.empty-ghost {
  position: absolute;
  font-family: var(--font-serif);
  font-size: 12rem;
  line-height: 1;
  color: var(--color-text);
  opacity: 0.04;
  pointer-events: none;
  user-select: none;
}

.empty-title {
  position: relative;
  margin: 0 0 6px;
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  color: var(--color-text);
}

.empty-tip {
  position: relative;
  margin-bottom: var(--space-4);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

.empty-btn {
  position: relative;
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-primary);
  font-size: var(--fs-small);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.empty-btn:hover {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

/* ========== 加载与页脚 ========== */
.wall-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-top: var(--space-5);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.wall-foot-note {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.wall-foot-end {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
  letter-spacing: var(--ls-wide);
}

.load-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-size: var(--fs-small);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-soft);
}

.load-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.load-btn:disabled {
  cursor: default;
  opacity: 0.7;
}

/* ========== 暗色适配 ========== */
:global([data-theme='dark']) .cover-avail {
  background: rgba(36, 32, 25, 0.84);
  color: var(--color-text);
}

:global([data-theme='dark']) .cover-btn {
  background: rgba(36, 32, 25, 0.86);
  border-color: rgba(255, 255, 255, 0.14);
  color: var(--color-text);
}

:global([data-theme='dark']) .cover-btn:hover {
  background: var(--color-bg-subtle);
}

:global([data-theme='dark']) .volume-cover {
  box-shadow: 0 16px 30px -22px rgba(0, 0, 0, 0.9);
}

:global([data-theme='dark']) .volume:hover .volume-cover {
  box-shadow: 0 24px 42px -24px rgba(0, 0, 0, 0.95);
}

/* ========== 响应式 ========== */
@media (max-width: 1200px) {
  .collection-body {
    grid-template-columns: 186px minmax(0, 1fr);
    gap: var(--space-5);
  }

  .wall {
    grid-template-columns: repeat(auto-fill, minmax(176px, 1fr));
    gap: 26px 18px;
  }

  .band-label {
    min-width: 0;
  }

  .band-eyebrow {
    display: none;
  }

  .band-sub {
    padding-left: 0;
  }
}

@media (max-width: 1024px) {
  .collection-body {
    grid-template-columns: 1fr;
    gap: var(--space-4);
  }

  .index-rail {
    position: static;
    max-height: none;
    border-right: none;
    border-bottom: 1px solid var(--color-border);
    padding-right: 0;
    padding-bottom: var(--space-4);
  }

  .rail-scroll {
    max-height: 28vh;
  }

  .rail-fade {
    background: linear-gradient(to bottom, transparent, var(--color-bg));
  }
}

@media (max-width: 768px) {
  .masthead {
    align-items: flex-start;
  }

  .masthead-side {
    display: none;
  }

  .search-band {
    margin: var(--space-3) calc(-1 * var(--space-4)) 0;
    padding: var(--space-3) var(--space-4);
  }

  .band-row {
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .band-label {
    display: none;
  }

  .band-input {
    flex: 1 1 100%;
  }

  .band-right {
    width: 100%;
    justify-content: space-between;
  }

  .band-sub {
    padding-left: 0;
    gap: var(--space-2);
  }

  .sub-hint {
    margin-left: 0;
    flex-basis: 100%;
  }

  .wall {
    grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
    gap: 22px 14px;
  }

  .wall-foot {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
  }

  .wall-foot-note {
    text-align: center;
  }

  .load-btn {
    width: 100%;
    justify-content: center;
  }
}
</style>
