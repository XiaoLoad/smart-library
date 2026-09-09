<script setup>
import { ref } from 'vue'

/**
 * 通用截断文本：内容溢出时悬停显示全文（el-tooltip），未截断时无悬停效果
 *
 * - content：完整文本内容（同时用于展示与 tooltip）
 * - lines：截断行数，1 = 单行 ellipsis；>1 = 多行 line-clamp（按高度检测溢出）
 *
 * 溢出判定在每次 mouseenter 时实时计算（scrollWidth/scrollHeight），
 * 窗口缩放、字体加载导致的截断状态变化无需监听即可自愈。
 */
const props = defineProps({
  content: { type: String, default: '' },
  lines: { type: Number, default: 1 },
})

const textRef = ref(null)
const truncated = ref(false)

const checkTruncated = () => {
  const el = textRef.value
  if (!el) return
  truncated.value =
    props.lines > 1
      ? el.scrollHeight > el.clientHeight + 1
      : el.scrollWidth > el.clientWidth + 1
}
</script>

<template>
  <el-tooltip
    :content="content"
    placement="top"
    effect="dark"
    :show-after="300"
    :disabled="!truncated"
  >
    <span
      ref="textRef"
      class="ellipsis-text"
      :class="{ multi: lines > 1 }"
      :style="lines > 1 ? { '-webkit-line-clamp': lines } : null"
      @mouseenter="checkTruncated"
    >{{ content }}</span>
  </el-tooltip>
</template>

<style scoped>
.ellipsis-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 多行截断：-webkit-line-clamp 行数由 :style 注入 */
.ellipsis-text.multi {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  white-space: normal;
  word-break: break-all;
}
</style>
