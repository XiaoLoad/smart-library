<template>
  <el-tree-select
    :model-value="modelValue"
    :data="data"
    :props="treeProps"
    :node-key="nodeKey"
    check-strictly
    filterable
    clearable
    :filter-node-method="filterNode"
    :placeholder="placeholder"
    :disabled="disabled"
    :render-after-expand="false"
    style="width: 100%"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @change="(v) => emit('change', v)"
  />
</template>

<script setup>
import { computed } from 'vue'

/**
 * 可搜索分类下拉树（复用组件）
 * 基于 el-tree-select：下拉展示树形结构，支持输入关键字模糊过滤，
 * 命中节点的祖先链会自动保留展示；父子节点选择互不关联（check-strictly），
 * 任意层级均可选中。
 *
 * 用法：
 *   <CategoryTreeSelect v-model="form.categoryId" :data="categories" placeholder="请选择分类" />
 */
const props = defineProps({
  modelValue: { type: [Number, String, null], default: null },
  /** 树形数据，节点需含 id / 分类名字段 / children */
  data: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  disabled: { type: Boolean, default: false },
  /** 节点显示文本字段名 */
  labelKey: { type: String, default: 'categoryName' },
  /** 节点值字段名 */
  nodeKey: { type: String, default: 'id' },
  /** 子节点字段名 */
  childrenKey: { type: String, default: 'children' },
})

const emit = defineEmits(['update:modelValue', 'change'])

const treeProps = computed(() => ({
  label: props.labelKey,
  children: props.childrenKey,
}))

// 模糊过滤：节点名包含关键字即命中（大小写不敏感）；
// el-tree 的过滤机制会自动展示命中节点的祖先链
const filterNode = (value, data) => {
  if (!value) return true
  const label = data[props.labelKey]
  if (label == null) return false
  return String(label).toLowerCase().includes(String(value).trim().toLowerCase())
}
</script>
