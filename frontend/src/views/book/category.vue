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
          <el-input v-model="filter" placeholder="搜索分类" :prefix-icon="Search" clearable class="search-input" />
        </div>
        <div class="right">
          <el-button type="primary" :icon="Plus" @click="openAddRoot">新增根分类</el-button>
        </div>
      </div>

      <el-table
        :data="categories"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        style="width: 100%"
      >
        <el-table-column prop="categoryName" label="分类名称" min-width="220" />
        <el-table-column prop="categoryCode" label="编码" width="160">
          <template #default="{ row }">
            <span class="mono-text">{{ row.categoryCode }}</span>
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
            <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openAddChild(row)">新增子级</el-button>
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
import { ref, reactive, computed, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bookApi } from '@/api'

const categories = ref([])
const filter = ref('')

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
</style>
