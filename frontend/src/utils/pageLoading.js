/* 内容区遮罩 loading */
/* 覆盖 .app-content 区域（侧边栏除外），适合页面首次加载 */
import { ElLoading } from 'element-plus'

let instance = null
let count = 0

export const showPageLoading = () => {
  count++
  if (count === 1) {
    instance = ElLoading.service({
      target: '.app-content',
      lock: false,
      text: '加载中...',
      background: 'rgba(0, 0, 0, 0.5)',
      customClass: 'page-loading-mask',
    })
  }
}

export const hidePageLoading = () => {
  if (count > 0) count--
  if (count === 0 && instance) {
    instance.close()
    instance = null
  }
}
