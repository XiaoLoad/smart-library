<template>
  <div class="page knowledge-page">
    <header class="page-header">
      <span class="eyebrow">知识库 · KNOWLEDGE BASE</span>
      <h1>知识库</h1>
      <p class="desc">人工上传图书馆规章文档，切分为向量片段后为 AI 助手提供检索依据。</p>
    </header>

    <div class="data-table">
      <!-- 工具条：检索筛选 + 文档录入 + 索引维护 -->
      <div class="table-toolbar">
        <div class="left">
          <el-input
            v-model="keyword"
            placeholder="搜索文档标题"
            clearable
            class="search-input"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select v-model="scopeFilter" class="scope-select" @change="onSearch">
            <el-option label="全部范围" value="" />
            <el-option label="公开" value="public" />
            <el-option label="内部" value="admin" />
          </el-select>
          <el-button @click="onSearch">搜索</el-button>
        </div>

        <div class="right">
          <!-- RAG 总开关：关闭后 AI 助手不再检索知识库 -->
          <span class="rag-switch">
            <span class="rag-switch__label">RAG 总开关</span>
            <el-switch :model-value="config.enabled" :loading="switchLoading" @change="onToggleRag" />
          </span>
          <!-- 上传范围：上传的 txt/md 不保留原文件、也无法事后改可见范围，故上传前先选 -->
          <el-select v-model="uploadScope" class="scope-select" title="上传文档的可见范围">
            <el-option label="上传为公开" value="public" />
            <el-option label="上传为内部" value="admin" />
          </el-select>
          <el-upload
            :show-file-list="false"
            accept=".txt,.md"
            :disabled="uploading"
            :before-upload="beforeUpload"
            :http-request="onUpload"
          >
            <el-button :loading="uploading">
              <span class="ico-btn"><AppIcon name="plus" :size="15" />上传 txt/md</span>
            </el-button>
          </el-upload>
          <el-button @click="openTextDialog">
            <span class="ico-btn"><AppIcon name="pencil" :size="15" />粘贴文本</span>
          </el-button>
          <el-button :loading="rebuildLoading" @click="onRebuildAll">
            <span class="ico-btn"><AppIcon name="refresh" :size="15" />全量重建索引</span>
          </el-button>
          <el-button type="primary" @click="openSearchDrawer">
            <span class="ico-btn"><AppIcon name="bolt" :size="15" />检索测试</span>
          </el-button>
        </div>
      </div>

      <!-- 检索参数 + 索引状态 -->
      <div v-loading="configLoading" class="config-bar">
        <div class="cfg-fields">
          <div class="cfg-field">
            <label>返回片段数</label>
            <el-input-number
              v-model="config.maxResults"
              :min="1"
              :max="10"
              :step="1"
              size="small"
              controls-position="right"
              class="cfg-input"
            />
          </div>
          <div class="cfg-field">
            <label>相似度下限</label>
            <el-input-number
              v-model="config.minScore"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="2"
              size="small"
              controls-position="right"
              class="cfg-input"
            />
          </div>
          <div class="cfg-field">
            <label>切片字数</label>
            <el-input-number
              v-model="config.chunkSize"
              :min="50"
              :max="2000"
              :step="10"
              size="small"
              controls-position="right"
              class="cfg-input"
            />
          </div>
          <div class="cfg-field">
            <label>重叠字数</label>
            <el-input-number
              v-model="config.chunkOverlap"
              :min="0"
              :max="500"
              :step="10"
              size="small"
              controls-position="right"
              class="cfg-input"
            />
          </div>
        </div>

        <div class="cfg-right">
          <span class="idx-status" :class="configReady ? 'is-ready' : 'is-pending'">
            <i class="idx-dot"></i>
            {{ configReady ? '索引已就绪' : '索引加载中' }} · 已索引 {{ indexedSegmentCount }} 片段
          </span>
          <el-button type="primary" size="small" :loading="configSaving" @click="saveConfigParams">
            保存参数
          </el-button>
        </div>

        <p class="cfg-hint">
          检索类参数保存后立即生效；修改「切片字数 / 重叠字数」后需点击「全量重建索引」，已有文档才会按新参数重新切分。
        </p>
      </div>

      <!-- 文档列表 -->
      <el-table
        v-loading="loading"
        :data="documents"
        style="width: 100%"
        max-height="calc(100vh - 290px)"
      >
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <EllipsisText :content="row.title" />
          </template>
        </el-table-column>
        <el-table-column label="来源类型" width="110">
          <template #default="{ row }">
            <span class="cell-sub">{{ sourceLabel(row.sourceType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="row.scope === 'public' ? 'success' : 'info'">
              {{ scopeLabel(row.scope) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="片段数" width="100">
          <template #default="{ row }">
            <span class="seg-count">{{ row.segmentCount ?? 0 }}</span>
          </template>
        </el-table-column>
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
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span class="cell-time">{{ formatDateTime(row.updateTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openSegments(row)">查看片段</el-button>
            <el-button text type="primary" size="small" :loading="row._reindexLoading" @click="onReindex(row)">
              重建
            </el-button>
            <el-button text type="danger" size="small" @click="onDelete(row)">删除</el-button>
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
          @current-change="loadDocuments"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 片段明细抽屉 -->
    <el-drawer
      v-model="segDrawerVisible"
      :title="segDoc ? `片段明细 · ${segDoc.title}` : '片段明细'"
      size="560px"
      direction="rtl"
      :append-to-body="true"
    >
      <div v-loading="segLoading" class="seg-list">
        <div v-for="s in segments" :key="s.id" class="seg-item">
          <div class="seg-head">#{{ s.seq }}</div>
          <p class="seg-text">{{ s.content }}</p>
        </div>
        <div v-if="!segLoading && !segments.length" class="drawer-empty">
          该文档暂无片段，可点击列表中的「重建」按当前参数重新切分。
        </div>
      </div>
    </el-drawer>

    <!-- 检索测试抽屉 -->
    <el-drawer v-model="searchVisible" title="检索测试" size="620px" direction="rtl" :append-to-body="true">
      <div class="search-form">
        <el-input
          v-model="searchForm.query"
          type="textarea"
          :rows="3"
          placeholder="输入一句读者可能会问的话，如：图书可以借多少天？"
        />
        <div class="search-opts">
          <div class="opt-item">
            <label>检索视角</label>
            <el-select v-model="searchForm.scope" size="small" class="opt-select">
              <el-option label="不过滤（管理员视角）" value="" />
              <el-option label="模拟读者视角" value="public" />
            </el-select>
          </div>
          <div class="opt-item">
            <label>返回条数</label>
            <el-input-number
              v-model="searchForm.maxResults"
              :min="1"
              :max="10"
              size="small"
              controls-position="right"
              placeholder="默认"
              class="opt-num"
            />
          </div>
          <div class="opt-item">
            <label>相似度下限</label>
            <el-input-number
              v-model="searchForm.minScore"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="2"
              size="small"
              controls-position="right"
              placeholder="默认"
              class="opt-num"
            />
          </div>
        </div>
        <div class="search-actions">
          <el-button type="primary" :loading="searching" @click="runSearch">检索</el-button>
        </div>
      </div>

      <div v-loading="searching" class="search-results">
        <div v-if="searched && !results.length" class="result-empty">
          未命中任何片段（可下调相似度下限）
        </div>
        <div v-for="(r, i) in results" :key="`${r.segmentId}-${i}`" class="result-item">
          <div class="result-head">
            <span class="result-score">{{ Number(r.score ?? 0).toFixed(4) }}</span>
            <span class="result-doc">{{ r.docTitle }}</span>
            <el-tag size="small" effect="light" :type="r.scope === 'public' ? 'success' : 'info'">
              {{ scopeLabel(r.scope) }}
            </el-tag>
          </div>
          <p class="result-text">{{ r.content }}</p>
        </div>
      </div>
    </el-drawer>

    <!-- 粘贴文本录入弹窗 -->
    <el-dialog v-model="textVisible" title="粘贴文本录入" width="600px" align-center :append-to-body="true">
      <el-form ref="textFormRef" :model="textForm" :rules="textRules" label-width="80px">
        <el-form-item label="文档标题" prop="title">
          <el-input v-model="textForm.title" placeholder="如：图书馆借阅规则（2026 版）" />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="textForm.scope">
            <el-radio value="public">公开（读者可检索）</el-radio>
            <el-radio value="admin">内部（仅管理员）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="正文内容" prop="content">
          <el-input
            v-model="textForm.content"
            type="textarea"
            :rows="12"
            placeholder="粘贴规章全文，系统按当前切片参数自动切分入库"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="textVisible = false">取消</el-button>
        <el-button type="primary" :loading="textSubmitting" @click="submitText">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/components/AppIcon.vue'
import EllipsisText from '@/components/EllipsisText.vue'
import { ragApi } from '@/api'
import { formatDateTime } from '@/utils/datetime.js'

/* 文档列表 */
const documents = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const scopeFilter = ref('')
const loading = ref(false)

const loadDocuments = async () => {
  loading.value = true
  try {
    const data = await ragApi.documents({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      scope: scopeFilter.value || undefined,
    })
    documents.value = data?.list || []
    total.value = data?.total || 0
  } catch {
    // 错误已由拦截器提示；清空避免展示上一次的过期数据
    documents.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// 条件变化一律回到第一页，否则可能落在超出范围的空白页
const onSearch = () => {
  page.value = 1
  loadDocuments()
}
const onSizeChange = () => {
  page.value = 1
  loadDocuments()
}

const sourceLabel = (t) => (t === 'manual' ? '人工录入' : t || '—')
const scopeLabel = (s) => (s === 'admin' ? '内部' : '公开')

/* 文档入库（上传 / 粘贴） */
const uploading = ref(false)
// 上传目标范围：内部（admin）文档只在管理员会话中可被检索到
const uploadScope = ref('public')

// 前端先挡一次非文本文件，避免把二进制读成乱码文档入库
const beforeUpload = (file) => {
  const name = (file.name || '').toLowerCase()
  if (!name.endsWith('.txt') && !name.endsWith('.md')) {
    ElMessage.warning('仅支持 .txt / .md 文本文件')
    return false
  }
  return true
}

const onUpload = async ({ file }) => {
  uploading.value = true
  try {
    const res = await ragApi.upload(file, { scope: uploadScope.value })
    ElMessage.success(res?.segmentCount ? `文档已入库，切分为 ${res.segmentCount} 个片段` : '文档已入库')
    page.value = 1
    await Promise.all([loadDocuments(), loadConfig()])
  } catch {
    // 错误已由拦截器提示
  } finally {
    uploading.value = false
  }
}

const textVisible = ref(false)
const textSubmitting = ref(false)
const textFormRef = ref(null)
const textForm = reactive({ title: '', scope: 'public', content: '' })
const textRules = {
  title: [{ required: true, message: '请输入文档标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入文档正文', trigger: 'blur' }],
}

const openTextDialog = () => {
  Object.assign(textForm, { title: '', scope: 'public', content: '' })
  textVisible.value = true
}

const submitText = async () => {
  if (!textFormRef.value) return
  await textFormRef.value.validate(async (valid) => {
    if (!valid) return
    textSubmitting.value = true
    try {
      const res = await ragApi.createByText({
        title: textForm.title.trim(),
        scope: textForm.scope,
        content: textForm.content,
      })
      ElMessage.success(res?.segmentCount ? `文档已入库，切分为 ${res.segmentCount} 个片段` : '文档已入库')
      textVisible.value = false
      page.value = 1
      await Promise.all([loadDocuments(), loadConfig()])
    } catch {
      // 错误已由拦截器提示
    } finally {
      textSubmitting.value = false
    }
  })
}

/* RAG 参数与索引状态 */
const configLoading = ref(false)
const configSaving = ref(false)
const switchLoading = ref(false)
const configReady = ref(false)
const indexedSegmentCount = ref(0)
// 默认值与后端 RagConfig 缺省一致，接口返回后覆盖
const config = reactive({ enabled: true, maxResults: 3, minScore: 0.72, chunkSize: 400, chunkOverlap: 60 })

const applyConfig = (cfg) => {
  if (!cfg) return
  if (cfg.enabled != null) config.enabled = !!cfg.enabled
  if (cfg.maxResults != null) config.maxResults = cfg.maxResults
  if (cfg.minScore != null) config.minScore = Number(cfg.minScore)
  if (cfg.chunkSize != null) config.chunkSize = cfg.chunkSize
  if (cfg.chunkOverlap != null) config.chunkOverlap = cfg.chunkOverlap
}

const loadConfig = async () => {
  configLoading.value = true
  try {
    const data = await ragApi.getConfig()
    applyConfig(data?.config)
    configReady.value = !!data?.ready
    indexedSegmentCount.value = data?.indexedSegmentCount ?? 0
  } catch {
    // 错误已由拦截器提示，保留默认参数
  } finally {
    configLoading.value = false
  }
}

// 总开关只改 enabled：其余字段不传，后端按 null 跳过，不会被本地默认值覆盖线上配置
const onToggleRag = async (enabled) => {
  if (switchLoading.value) return
  switchLoading.value = true
  try {
    const saved = await ragApi.saveConfig({ enabled })
    applyConfig(saved)
    ElMessage.success(enabled ? 'RAG 已开启，AI 助手将检索知识库' : 'RAG 已关闭，AI 助手不再检索知识库')
  } catch {
    // 失败时开关保持原值（:model-value 单向绑定，未做本地修改）
  } finally {
    switchLoading.value = false
  }
}

const saveConfigParams = async () => {
  configSaving.value = true
  try {
    const saved = await ragApi.saveConfig({
      maxResults: config.maxResults,
      minScore: config.minScore,
      chunkSize: config.chunkSize,
      chunkOverlap: config.chunkOverlap,
    })
    applyConfig(saved)
    ElMessage.success('检索参数已保存；切片参数需「全量重建索引」后生效')
  } catch {
    // 错误已由拦截器提示
  } finally {
    configSaving.value = false
  }
}

/* 索引维护 */
const rebuildLoading = ref(false)

// 重建含逐片向量化，耗时随文档量增长，先二次确认再执行
const onRebuildAll = async () => {
  try {
    await ElMessageBox.confirm(
      '将按当前切片参数对所有启用文档重新切分并逐片向量化，文档较多时耗时较长。确定继续？',
      '全量重建索引',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' },
    )
  } catch {
    return // 取消
  }
  rebuildLoading.value = true
  try {
    const res = await ragApi.rebuildAll()
    ElMessage.success(`全量重建完成，共生成 ${res?.segmentCount ?? 0} 个片段`)
    await Promise.all([loadDocuments(), loadConfig()])
  } catch {
    // 错误已由拦截器提示
  } finally {
    rebuildLoading.value = false
  }
}

const onReindex = async (row) => {
  row._reindexLoading = true
  try {
    const res = await ragApi.reindex(row.id)
    ElMessage.success(`「${row.title}」索引已重建，共 ${res?.segmentCount ?? 0} 个片段`)
    await loadDocuments()
  } catch {
    // 错误已由拦截器提示
  } finally {
    row._reindexLoading = false
  }
}

/* 状态启停 / 删除 */
const onStatusChange = async (row, next) => {
  row._statusLoading = true
  try {
    const res = await ragApi.updateStatus(row.id, next)
    ElMessage.success(res?.message || (next === 1 ? '文档已启用' : '文档已停用'))
  } catch {
    // 错误已由拦截器提示
  } finally {
    row._statusLoading = false
    await loadDocuments()
  }
}

const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${row.title}」？其 ${row.segmentCount ?? 0} 个片段将一并删除，删除后不可恢复。`,
      '删除文档',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return // 取消
  }
  try {
    const res = await ragApi.deleteDocument(row.id)
    ElMessage.success(res?.message || '文档已删除')
    // 删掉当页最后一条时回退一页，避免停留在空白页
    if (documents.value.length === 1 && page.value > 1) page.value -= 1
    await loadDocuments()
  } catch {
    // 错误已由拦截器提示
  }
}

/* 片段明细 */
const segDrawerVisible = ref(false)
const segLoading = ref(false)
const segDoc = ref(null)
const segments = ref([])

const openSegments = async (row) => {
  segDoc.value = row
  segments.value = []
  segDrawerVisible.value = true
  segLoading.value = true
  try {
    const list = await ragApi.segments(row.id)
    segments.value = Array.isArray(list) ? list : []
  } catch {
    segments.value = []
  } finally {
    segLoading.value = false
  }
}

/* 检索测试 */
const searchVisible = ref(false)
const searching = ref(false)
const searched = ref(false)
const results = ref([])
// maxResults / minScore 留空（null）表示沿用后端配置
const searchForm = reactive({ query: '', scope: '', maxResults: null, minScore: null })

const openSearchDrawer = () => {
  searchVisible.value = true
}

const runSearch = async () => {
  const query = searchForm.query.trim()
  if (!query) {
    ElMessage.warning('请输入要检索的内容')
    return
  }
  searching.value = true
  try {
    const list = await ragApi.searchTest({
      query,
      maxResults: searchForm.maxResults ?? undefined,
      minScore: searchForm.minScore ?? undefined,
      scope: searchForm.scope || undefined,
    })
    // 后端已按相似度排序，这里再兜底一次，保证并列分数也稳定降序展示
    results.value = (Array.isArray(list) ? [...list] : []).sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
    searched.value = true
  } catch {
    // 检索失败不置 searched，避免误报成「未命中」
    results.value = []
  } finally {
    searching.value = false
  }
}

onMounted(() => {
  loadDocuments()
  loadConfig()
})
</script>

<style scoped>
/* 工具条右侧按钮组：全局只在小屏定义了 .right 的 flex，此处补齐桌面端间距 */
.knowledge-page .table-toolbar .right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.rag-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin-right: var(--space-2);
}

.rag-switch__label {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

/* 带图标的按钮内容：el-button 默认不给 AppIcon 留间距 */
.ico-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.scope-select {
  width: 130px;
}

/* 参数区：与工具栏同卡，用浅底区分「配置」与「数据」 */
.config-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3) var(--space-4);
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--color-divider);
  background: var(--color-bg-subtle);
}

.cfg-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-4);
}

.cfg-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.cfg-field label {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.cfg-input {
  width: 118px;
}

.cfg-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-left: auto;
}

.idx-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-caption);
  white-space: nowrap;
  color: var(--color-text-tertiary);
}

.idx-status.is-ready {
  color: var(--color-success);
}

.idx-status.is-pending {
  color: var(--color-warning);
}

.idx-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.cfg-hint {
  flex-basis: 100%;
  margin: 0;
  font-size: var(--fs-caption);
  line-height: 1.6;
  color: var(--color-text-tertiary);
}

/* 表格单元 */
.cell-sub {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.seg-count {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
}

.cell-time {
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-secondary);
}

/* 片段明细 */
.seg-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 60px;
}

.seg-item {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.seg-head {
  margin-bottom: 6px;
  font-family: var(--font-mono);
  font-size: var(--fs-caption);
  color: var(--color-text-tertiary);
}

.seg-text {
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  line-height: 1.8;
  color: var(--color-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.drawer-empty {
  padding: var(--space-6) 0;
  text-align: center;
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

/* 检索测试 */
.search-form {
  margin-bottom: var(--space-4);
}

.search-opts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
}

.opt-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.opt-item label {
  font-size: var(--fs-small);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.opt-select {
  width: 176px;
}

.opt-num {
  width: 118px;
}

.search-actions {
  margin-top: var(--space-3);
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 80px;
}

.result-empty {
  padding: var(--space-6) 0;
  text-align: center;
  font-size: var(--fs-small);
  color: var(--color-text-tertiary);
}

.result-item {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-left: 2px solid var(--color-primary);
  border-radius: var(--radius-md);
}

.result-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: 6px;
}

.result-score {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  font-weight: 600;
  color: var(--color-primary);
}

.result-doc {
  font-size: var(--fs-small);
  color: var(--color-text);
}

.result-text {
  margin: 0;
  max-height: 180px;
  overflow-y: auto;
  font-size: var(--fs-small);
  line-height: 1.7;
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 响应式 */
@media (max-width: 768px) {
  .config-bar {
    padding: var(--space-3);
  }

  .cfg-input {
    width: 100px;
  }

  .cfg-right {
    margin-left: 0;
  }
}
</style>