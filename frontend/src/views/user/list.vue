<template>
  <div class="page user-page" ref="pageRef">
    <header class="page-header">
      <span class="eyebrow">读者 · READERS</span>
      <h1>读者管理</h1>
      <p class="desc">共 {{ total }} 位在册读者。</p>
    </header>

    <div class="data-table fade-in" v-if="list.length || total">
      <div class="table-toolbar">
        <div class="left">
          <el-input v-model="search" placeholder="用户名 / 姓名" :prefix-icon="Search" clearable class="search-input" @keyup.enter="load" />
          <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 140px" @change="onTypeFilterChange">
            <el-option label="全部类型" :value="''" />
            <el-option label="教师" :value="1" />
            <el-option label="学生" :value="2" />
            <el-option label="管理员" :value="3" />
          </el-select>
          <el-button @click="load">搜索</el-button>
        </div>
        <div class="right">
          <el-button v-if="userStore.isAdmin" type="primary" :icon="Plus" @click="openAdd">新增读者</el-button>
        </div>
      </div>

      <el-table :data="filteredTypeList" style="width: 100%">
        <el-table-column label="读者" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="36" class="avatar">{{ row.realName.charAt(0) }}</el-avatar>
              <div>
                <div class="user-name">{{ row.realName }}</div>
                <div class="user-id">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <span class="status-tag" :class="typeClass(row.userType)">{{ typeText(row.userType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="编号" width="160">
          <template #default="{ row }">
            <span class="mono-text">{{ row.teacherNo || row.studentNo || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="院系" width="120" />
        <el-table-column prop="phone" label="联系电话" width="140">
          <template #default="{ row }">
            <span class="mono-text">{{ row.phone || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="加入时间" width="160">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="userStore.isAdmin" text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 新增/编辑读者弹窗 -->
    <el-dialog v-model="userDialogVisible" :title="userDialogTitle" width="600px" align-center>
      <el-form :model="userForm" label-width="100px" ref="userFormRef" :rules="userRules">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="userForm.username" :disabled="userForm.mode === 'edit'" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item v-if="userForm.mode === 'add'" label="密码" prop="password">
          <el-input v-model="userForm.password" type="password" show-password placeholder="初始密码" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="userForm.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="类型" prop="userType">
          <el-select v-model="userForm.userType" style="width: 100%">
            <el-option label="教师" :value="1" />
            <el-option label="学生" :value="2" />
            <el-option label="管理员" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" placeholder="邮箱" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="userForm.phone" placeholder="手机号" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 1" label="工号">
          <el-input v-model="userForm.teacherNo" placeholder="教师工号" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 1" label="院系">
          <el-input v-model="userForm.department" placeholder="院系" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 1" label="职称">
          <el-input v-model="userForm.title" placeholder="如：教授、副教授" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 2" label="学号">
          <el-input v-model="userForm.studentNo" placeholder="学号" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 2" label="班级">
          <el-input v-model="userForm.className" placeholder="班级" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 2" label="年级">
          <el-input v-model="userForm.grade" placeholder="如：2026" />
        </el-form-item>
        <el-form-item v-if="userForm.userType === 2" label="专业">
          <el-input v-model="userForm.major" placeholder="专业" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="userForm.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userLoading" @click="submitUser">确认</el-button>
      </template>
    </el-dialog>

    <!-- 读者详情弹窗 -->
    <el-dialog v-model="detailVisible" title="读者详情" width="500px" align-center>
      <div v-if="detailData" class="detail-content">
        <div class="detail-header">
          <el-avatar :size="56" class="avatar">{{ detailData.realName?.charAt(0) }}</el-avatar>
          <div>
            <div class="detail-name">{{ detailData.realName }}</div>
            <div class="detail-id">@{{ detailData.username }}</div>
          </div>
        </div>
        <div class="detail-grid">
          <div class="detail-item"><span class="label">类型</span><span class="value">{{ typeText(detailData.userType) }}</span></div>
          <div class="detail-item"><span class="label">角色</span><span class="value">{{ detailData.roleName }}</span></div>
          <div class="detail-item" v-if="detailData.teacherNo"><span class="label">工号</span><span class="value">{{ detailData.teacherNo }}</span></div>
          <div class="detail-item" v-if="detailData.studentNo"><span class="label">学号</span><span class="value">{{ detailData.studentNo }}</span></div>
          <div class="detail-item" v-if="detailData.department"><span class="label">院系</span><span class="value">{{ detailData.department }}</span></div>
          <div class="detail-item" v-if="detailData.title"><span class="label">职称</span><span class="value">{{ detailData.title }}</span></div>
          <div class="detail-item" v-if="detailData.className"><span class="label">班级</span><span class="value">{{ detailData.className }}</span></div>
          <div class="detail-item" v-if="detailData.grade"><span class="label">年级</span><span class="value">{{ detailData.grade }}</span></div>
          <div class="detail-item" v-if="detailData.major"><span class="label">专业</span><span class="value">{{ detailData.major }}</span></div>
          <div class="detail-item"><span class="label">邮箱</span><span class="value">{{ detailData.email || '-' }}</span></div>
          <div class="detail-item"><span class="label">电话</span><span class="value">{{ detailData.phone || '-' }}</span></div>
          <div class="detail-item"><span class="label">状态</span><span class="value">{{ detailData.status === 1 ? '正常' : '禁用' }}</span></div>
          <div class="detail-item"><span class="label">加入时间</span><span class="value">{{ formatTime(detailData.createTime) }}</span></div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { showPageLoading, hidePageLoading } from '@/utils/pageLoading.js'
import { useScrollRestore } from '@/composables/useScrollRestore'
import { useSnapshotRefresh } from '@/composables/useSnapshotRefresh'

// 缓存名与路由 name 一致，keep-alive 与滚动恢复都依赖它
defineOptions({ name: 'UserList' })

const pageRef = ref(null)
useScrollRestore(pageRef)

const userStore = useUserStore()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const search = ref('')
const typeFilter = ref('')

const filteredTypeList = computed(() => {
  // 空值兼容：'' / null / undefined（clearable 清空后为 undefined）均视为全部类型
  if (typeFilter.value === '' || typeFilter.value === null || typeFilter.value === undefined) {
    return list.value
  }
  return list.value.filter((u) => u.userType === typeFilter.value)
})

// 清空选择时 el-select 会发出 undefined，归一化为 ''，与「全部类型」选项一致
const onTypeFilterChange = (val) => {
  if (val === undefined || val === null) typeFilter.value = ''
}

// 新增/编辑
const userDialogVisible = ref(false)
const userLoading = ref(false)
const userFormRef = ref(null)
const userForm = reactive({
  mode: 'add',
  id: null,
  username: '',
  password: '',
  realName: '',
  userType: 2,
  email: '',
  phone: '',
  teacherNo: '',
  department: '',
  title: '',
  studentNo: '',
  className: '',
  grade: '',
  major: '',
  status: 1,
})
const userRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  userType: [{ required: true, message: '请选择类型', trigger: 'change' }],
}
const userDialogTitle = computed(() => userForm.mode === 'edit' ? '编辑读者' : '新增读者')

// 详情
const detailVisible = ref(false)
const detailData = ref(null)

const fetchSnapshot = async () => {
  const data = await userApi.list({ page: page.value, size: size.value, keyword: search.value })
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

// 页面自身的加载（首屏、翻页、搜索）带遮罩；激活比对走静默路径，不闪遮罩
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
  return String(t).replace('T', ' ').substring(0, 19)
}

const typeText = (t) => ({ 1: '教师', 2: '学生', 3: '管理员' }[t] || '')
const typeClass = (t) => ({ 1: '', 2: 'active', 3: 'returned' }[t] || '')

const resetForm = () => {
  Object.assign(userForm, {
    mode: 'add', id: null, username: '', password: '', realName: '', userType: 2,
    email: '', phone: '', teacherNo: '', department: '', title: '',
    studentNo: '', className: '', grade: '', major: '', status: 1,
  })
}

const openAdd = () => {
  resetForm()
  userDialogVisible.value = true
}

const openEdit = (row) => {
  Object.assign(userForm, {
    mode: 'edit', id: row.id, username: row.username, password: '', realName: row.realName,
    userType: row.userType, email: row.email || '', phone: row.phone || '',
    teacherNo: row.teacherNo || '', department: row.department || '', title: row.title || '',
    studentNo: row.studentNo || '', className: row.className || '', grade: row.grade || '',
    major: row.major || '', status: row.status,
  })
  userDialogVisible.value = true
}

const openDetail = async (row) => {
  try {
    const data = await userApi.detail(row.id)
    detailData.value = data
    detailVisible.value = true
  } catch {
    // 错误已由拦截器提示
  }
}

const submitUser = async () => {
  if (!userFormRef.value) return
  await userFormRef.value.validate(async (valid) => {
    if (!valid) return
    userLoading.value = true
    try {
      if (userForm.mode === 'edit') {
        const { password, ...rest } = userForm
        const res = await userApi.update(rest)
        ElMessage.success(res.message || '编辑成功')
      } else {
        const res = await userApi.add({ ...userForm })
        ElMessage.success(res.message || '新增成功')
      }
      userDialogVisible.value = false
      await load()
    } finally {
      userLoading.value = false
    }
  })
}

onMounted(load)
</script>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.avatar {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  font-family: var(--font-serif);
  font-weight: 600;
}
.user-name {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--color-text);
}
.user-id {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}
.time-text {
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}
/* 详情 */
.detail-header {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-divider);
}
.detail-name {
  font-family: var(--font-serif);
  font-size: var(--fs-h3);
  font-weight: 600;
}
.detail-id {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}
.detail-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.detail-item .label {
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}
.detail-item .value {
  font-size: var(--fs-body);
  color: var(--color-text);
}
</style>
