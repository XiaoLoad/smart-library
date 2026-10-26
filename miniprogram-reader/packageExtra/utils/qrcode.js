/* 取书二维码绘制（canvas 2d + qrcode-generator）
 * 使用前请在开发者工具执行：工具 → 构建 npm
 */
let qrcodeGen = null
try {
  qrcodeGen = require('qrcode-generator')
} catch (e) {
  console.warn('[qrcode] qrcode-generator 未安装/未构建 npm，二维码功能不可用')
}

/**
 * 在 canvas 2d 节点上绘制二维码
 * @param {object} canvasNode 通过 wx.createSelectorQuery().node() 获取
 * @param {string} text 二维码内容（借阅记录 qr_payload）
 * @param {number} sizePx 逻辑尺寸（px）
 * @returns {boolean} 是否绘制成功
 */
function draw(canvasNode, text, sizePx) {
  if (!qrcodeGen || !canvasNode) return false
  const qr = qrcodeGen(0, 'M')
  qr.addData(text || ' ')
  qr.make()

  const count = qr.getModuleCount()
  const dpr = wx.getSystemInfoSync().pixelRatio || 2
  canvasNode.width = sizePx * dpr
  canvasNode.height = sizePx * dpr
  const ctx = canvasNode.getContext('2d')
  ctx.scale(dpr, dpr)

  // 白底
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, sizePx, sizePx)

  const cell = sizePx / count
  ctx.fillStyle = '#000000'
  // 逐行游程合并矩形，降低 draw 次数
  for (let r = 0; r < count; r++) {
    let runStart = -1
    for (let c = 0; c <= count; c++) {
      const dark = c < count && qr.isDark(r, c)
      if (dark && runStart === -1) runStart = c
      if (!dark && runStart !== -1) {
        ctx.fillRect(runStart * cell, r * cell, (c - runStart) * cell, cell)
        runStart = -1
      }
    }
  }
  return true
}

module.exports = { draw, available: !!qrcodeGen }
