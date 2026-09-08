<template>
  <div class="page category-page">
    <header class="page-header">
      <span class="eyebrow">分类 · TAXONOMY</span>
      <h1>图书分类</h1>
      <p class="desc">树形多级分类管理。</p>
    </header>

    <div class="data-table">
      <div class="table-toolbar">
        <div class="left">
          <el-input v-model="filter" placeholder="搜索名称或编码，如 文学 / LIT" :prefix-icon="Search" clearable class="search-input" />
          <transition name="fade">
            <span v-if="filter.trim()" class="hit-count">{{ matchList.length }} 处命中</span>
          </transition>
        </div>
        <div class="right">
          <span class="tree-tools">
            <el-tooltip content="展开全部分类" placement="top" effect="dark" :show-after="300">
              <button type="button" class="tree-tool" @click="expandAll">
                <el-icon><Expand /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="收起全部分类" placement="top" effect="dark" :show-after="300">
              <button type="button" class="tree-tool" @click="collapseAll">
                <el-icon><Fold /></el-icon>
              </button>
            </el-tooltip>
          </span>
          <el-button type="primary" :icon="Plus" @click="openAddRoot">新增根分类</el-button>
        </div>
      </div>

      <!-- 命中路径面板：以「祖先链 / 命中节点」形式展示搜索结果 -->
      <transition name="drop">
        <div v-if="filter.trim() && matchList.length" class="search-panel">
          <div class="panel-head">
            <span class="panel-label">NODE PATH · 命中路径</span>
            <span class="panel-tip">点击路径可在树中定位</span>
          </div>
          <div class="path-list">
            <button
              v-for="item in matchList"
              :key="item.id"
              type="button"
              class="path-chip"
              @click="locateNode(item)"
            >
              <template v-for="(seg, i) in item.path" :key="i">
                <span v-if="i" class="sep">/</span>
                <span :class="['seg', i === item.path.length - 1 ? 'hit' : '']">{{ seg }}</span>
              </template>
              <span class="chip-level">L{{ item.level }}</span>
            </button>
          </div>
        </div>
      </transition>

      <!-- 搜索无结果的优雅空态 -->
      <div v-if="filter.trim() && !matchList.length" class="search-empty">
        <span class="empty-mark">∅</span>
        <p class="empty-title">未找到匹配「{{ filter.trim() }}」的分类</p>
        <span class="empty-tip">尝试更短的关键词，或按分类编码搜索（如 LIT）</span>
      </div>

      <el-table
        v-show="!filter.trim() || matchList.length"
        :data="filteredCategories"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :expand-row-keys="expandedKeys"
        :row-class-name="rowClassName"
        style="width: 100%"
      >
        <el-table-column prop="categoryName" label="分类名称" min-width="220">
          <template #default="{ row }">
            <span class="cate-name">
              <template v-for="(seg, i) in splitHighlight(row.categoryName)" :key="i">
                <mark v-if="seg.hit" class="hl">{{ seg.text }}</mark>
                <template v-else>{{ seg.text }}</template>
              </template>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="categoryCode" label="编码" width="160">
          <template #default="{ row }">
            <span class="mono-text">
              <template v-for="(seg, i) in splitHighlight(row.categoryCode)" :key="i">
                <mark v-if="seg.hit" class="hl">{{ seg.text }}</mark>
                <template v-else>{{ seg.text }}</template>
              </template>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="level" label="层级" width="100">
          <template #default="{ row }">
            <span class="status-tag">{{ 'L' + row.level }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status"
              :active-value="1"
              :inactive-value="0"
              :loading="row._statusLoading"
              @change="onStatusChange(row, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.status === 0"
              content="上级分类已禁用，需先启用分类"
              placement="top"
              effect="dark"
              :show-after="300"
            >
              <span>
                <el-button text type="primary" size="small" disabled>新增子级</el-button>
              </span>
            </el-tooltip>
            <el-button v-else text type="primary" size="small" @click="openAddChild(row)">新增子级</el-button>
            <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增/编辑分类弹窗 -->
    <el-dialog v-model="cateDialogVisible" :title="dialogTitle" width="480px" align-center>
      <el-form :model="cateForm" label-width="90px" ref="cateFormRef" :rules="cateRules">
        <el-form-item v-if="cateForm.parentName" label="上级分类">
          <el-input :model-value="cateForm.parentName" disabled />
        </el-form-item>
        <el-form-item label="分类名称" prop="categoryName">
          <el-input v-model="cateForm.categoryName" placeholder="如 文学" />
        </el-form-item>
        <el-form-item label="分类编码" prop="categoryCode">
          <el-input v-model="cateForm.categoryCode" placeholder="如 LIT" :disabled="cateForm.mode === 'edit'" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="cateForm.sortOrder" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="cateLoading" @click="submitCate">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted } from 'vue'
import { Search, Plus, Expand, Fold } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bookApi } from '@/api'

const categories = ref([])
const filter = ref('')
const pulseId = ref(null)

/* ---------- 分类搜索：过滤树 + 命中路径 + 定位 ---------- */
const normalized = (s) => (s || '').toString().toLowerCase()

// 递归过滤：保留命中节点及其完整祖先链
const filterTree = (nodes, q) =>
  nodes
    .map((n) => {
      const children = n.children ? filterTree(n.children, q) : []
      const hit = normalized(n.categoryName).includes(q) || normalized(n.categoryCode).includes(q)
      return hit || children.length ? { ...n, children } : null
    })
    .filter(Boolean)

const filteredCategories = computed(() => {
  const q = normalized(filter.value.trim())
  if (!q) return categories.value
  return filterTree(categories.value, q)
})

// 展开过滤后的全部节点（含祖先链），保证命中分支可见
const collectIds = (nodes, acc = []) => {
  nodes.forEach((n) => {
    acc.push(String(n.id))
    if (n.children) collectIds(n.children, acc)
  })
  return acc
}
// 展开状态受控：默认全展开，搜索时自动展开命中分支，支持一键展开/收起
const expandedKeys = ref([])
const expandAll = () => {
  expandedKeys.value = collectIds(filteredCategories.value)
}
const collapseAll = () => {
  expandedKeys.value = []
}
watch(categories, () => nextTick(expandAll))
watch(filter, (v) => nextTick(expandAll))

// 收集命中节点及其祖先路径，如 ['文学', '小说', '科幻']
const flattenHits = (nodes, path, acc) =>
  nodes.forEach((n) => {
    const p = [...path, n.categoryName]
    const q = normalized(filter.value.trim())
    if (q && (normalized(n.categoryName).includes(q) || normalized(n.categoryCode).includes(q))) {
      acc.push({ id: n.id, level: n.level, path: p })
    }
    if (n.children) flattenHits(n.children, p, acc)
  })

const matchList = computed(() => {
  if (!filter.value.trim()) return []
  const acc = []
  flattenHits(filteredCategories.value, [], acc)
  return acc
})

// 命中子串高亮分段（大小写不敏感）
const splitHighlight = (text) => {
  const q = filter.value.trim()
  const str = (text || '').toString()
  if (!q) return [{ text: str, hit: false }]
  const idx = str.toLowerCase().indexOf(q.toLowerCase())
  if (idx === -1) return [{ text: str, hit: false }]
  return [
    { text: str.slice(0, idx), hit: false },
    { text: str.slice(idx, idx + q.length), hit: true },
    { text: str.slice(idx + q.length), hit: false },
  ].filter((s) => s.text)
}

// 点击路径：滚动到对应行并闪烁提示
const rowClassName = ({ row }) =>
  [row.status === 0 ? 'row-disabled' : '', row.id === pulseId.value ? 'row-pulse' : '']
    .filter(Boolean)
    .join(' ')
const locateNode = (item) => {
  pulseId.value = item.id
  nextTick(() => {
    const el = document.querySelector('.category-page .el-table__body tr.row-pulse')
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  setTimeout(() => {
    if (pulseId.value === item.id) pulseId.value = null
  }, 1600)
}

/* ---------- 分类启用状态切换 ---------- */
// 统计子孙分类数量（含自身），用于禁用前的级联提示
const countDescendants = (targetId) => {
  let count = 0
  const walk = (nodes) =>
    nodes.forEach((n) => {
      if (n.id === targetId) {
        count++
        const collect = (children) =>
          children.forEach((c) => {
            count++
            if (c.children) collect(c.children)
          })
        if (n.children) collect(n.children)
        return
      }
      if (n.children) walk(n.children)
    })
  walk(categories.value)
  return count
}

const onStatusChange = async (row, next) => {
  if (next === 0) {
    const count = countDescendants(row.id)
    const tip =
      count > 1
        ? `其下 ${count - 1} 个子分类将一并停用`
        : '该分类暂无子分类'
    try {
      await ElMessageBox.confirm(`确定禁用「${row.categoryName}」？${tip}。`, '禁用分类', {
        type: 'warning',
        confirmButtonText: '禁用',
        cancelButtonText: '取消',
      })
    } catch {
      return // 取消操作
    }
  }
  row._statusLoading = true
  try {
    const res = await bookApi.updateCategoryStatus({ id: row.id, status: next })
    ElMessage.success(res.message || (next === 1 ? '已启用' : '已禁用'))
  } catch {
    // 后端校验失败（如父分类未启用），刷新回弹状态
  } finally {
    row._statusLoading = false
    await load()
  }
}

// 新增/编辑分类弹窗
const cateDialogVisible = ref(false)
const cateLoading = ref(false)
const cateFormRef = ref(null)
const cateForm = reactive({
  mode: 'add', // add | edit
  id: null,
  parentId: 0,
  parentName: '',
  categoryName: '',
  categoryCode: '',
  sortOrder: 1,
})
const cateRules = {
  categoryName: [{ required: true, message: '请输入分类名称', trigger: 'blur' }],
  categoryCode: [{ required: true, message: '请输入分类编码', trigger: 'blur' }],
}

const dialogTitle = computed(() => {
  if (cateForm.mode === 'edit') return '编辑分类'
  return cateForm.parentId ? '新增子分类' : '新增根分类'
})

const load = async () => {
  const data = await bookApi.categoryTree()
  categories.value = data
}

const openAddRoot = () => {
  Object.assign(cateForm, { mode: 'add', id: null, parentId: 0, parentName: '', categoryName: '', categoryCode: '', sortOrder: 1 })
  cateDialogVisible.value = true
}

const openAddChild = (row) => {
  Object.assign(cateForm, { mode: 'add', id: null, parentId: row.id, parentName: row.categoryName, categoryName: '', categoryCode: '', sortOrder: 1 })
  cateDialogVisible.value = true
}

const openEdit = (row) => {
  Object.assign(cateForm, {
    mode: 'edit',
    id: row.id,
    parentId: row.parentId,
    parentName: '',
    categoryName: row.categoryName,
    categoryCode: row.categoryCode,
    sortOrder: row.sortOrder,
  })
  cateDialogVisible.value = true
}

const submitCate = async () => {
  if (!cateFormRef.value) return
  await cateFormRef.value.validate(async (valid) => {
    if (!valid) return
    cateLoading.value = true
    try {
      if (cateForm.mode === 'edit') {
        const res = await bookApi.updateCategory({
          id: cateForm.id,
          categoryName: cateForm.categoryName,
          sortOrder: cateForm.sortOrder,
        })
        ElMessage.success(res.message || '编辑成功')
      } else {
        const res = await bookApi.addCategory({
          categoryName: cateForm.categoryName,
          categoryCode: cateForm.categoryCode,
          parentId: cateForm.parentId,
          sortOrder: cateForm.sortOrder,
        })
        ElMessage.success(res.message || '新增成功')
      }
      cateDialogVisible.value = false
      await load()
    } finally {
      cateLoading.value = false
    }
  })
}

onMounted(load)
</script>

<style scoped>
.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

/* 工具栏命中计数 */
.hit-count {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-primary, #1f3a93);
  letter-spacing: 0.08em;
  white-space: nowrap;
}

/* 树展开/收起工具 */
.tree-tools {
  display: inline-flex;
  gap: 4px;
  margin-right: 10px;
}
.tree-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary, #6b7280);
  cursor: pointer;
  transition: all 0.18s ease;
}
.tree-tool:hover {
  color: var(--color-primary, #1f3a93);
  border-color: var(--color-border, rgba(17, 24, 39, 0.1));
  background: var(--color-bg-soft, rgba(17, 24, 39, 0.02));
}

/* 命中路径面板 */
.search-panel {
  margin: 4px 0 14px;
  padding: 12px 16px 14px;
  border: 1px solid var(--color-border, rgba(17, 24, 39, 0.1));
  border-left: 2px solid var(--color-primary, #1f3a93);
  border-radius: var(--radius-md, 10px);
  background: var(--color-bg-soft, rgba(17, 24, 39, 0.015));
}
.panel-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
}
.panel-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  color: var(--color-text-secondary, #6b7280);
}
.panel-tip {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}
.path-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.path-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px;
  border: 1px solid var(--color-border, rgba(17, 24, 39, 0.1));
  border-radius: 999px;
  background: var(--color-surface, #fff);
  font-size: 13px;
  color: var(--color-text-secondary, #4b5563);
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.path-chip:hover {
  border-color: var(--color-primary, #1f3a93);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(17, 24, 39, 0.08);
}
.path-chip .seg.hit {
  color: var(--color-primary, #1f3a93);
  font-weight: 600;
}
.path-chip .sep {
  color: var(--color-text-tertiary, #c0c4cc);
  font-size: 11px;
}
.chip-level {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-text-tertiary, #9ca3af);
  border-left: 1px solid var(--color-border, rgba(17, 24, 39, 0.12));
  padding-left: 8px;
}

/* 表格内命中高亮 */
.hl {
  background: transparent;
  color: var(--color-primary, #1f3a93);
  font-weight: 600;
  border-bottom: 1px solid color-mix(in srgb, var(--color-primary, #1f3a93) 40%, transparent);
  padding: 0 1px;
}

/* 禁用分类行整体弱化 */
:deep(tr.row-disabled) .cate-name,
:deep(tr.row-disabled) .mono-text {
  color: var(--color-text-tertiary, #9ca3af);
}

/* 定位行的闪烁提示 */
:deep(tr.row-pulse td) {
  animation: rowPulse 1.6s ease;
}
@keyframes rowPulse {
  0%,
  55% {
    background: color-mix(in srgb, var(--color-primary, #1f3a93) 10%, transparent);
  }
  100% {
    background: transparent;
  }
}

/* 搜索无结果空态 */
.search-empty {
  padding: 56px 0 60px;
  text-align: center;
  border: 1px dashed var(--color-border, rgba(17, 24, 39, 0.12));
  border-radius: var(--radius-md, 10px);
  margin-bottom: 16px;
}
.empty-mark {
  display: block;
  font-size: 34px;
  color: var(--color-text-tertiary, #c0c4cc);
  margin-bottom: 10px;
}
.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary, #1f2937);
  margin: 0 0 6px;
}
.empty-tip {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.drop-enter-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.drop-leave-active {
  transition: opacity 0.15s ease;
}
.drop-enter-from {
  opacity: 0;
  transform: translateY(-6px);
}
.drop-leave-to {
  opacity: 0;
}
</style>
