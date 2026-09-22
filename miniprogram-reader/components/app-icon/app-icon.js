// 线性图标组件：<app-icon name="search" size="28" color="#9c958d"/>
const { getIcon } = require('../../utils/icons')

const SCREEN_WIDTH = wx.getSystemInfoSync().windowWidth || 375

Component({
  properties: {
    name: { type: String, value: 'book' },
    color: { type: String, value: '#6b6560' },
    size: { type: Number, value: 32 }, // rpx
  },
  data: { src: '', px: 16 },
  observers: {
    'name, color, size': function (name, color, size) {
      this.setData({
        src: getIcon(name, color),
        px: Math.max(12, Math.round(((size || 32) * SCREEN_WIDTH) / 750)),
      })
    },
  },
})
