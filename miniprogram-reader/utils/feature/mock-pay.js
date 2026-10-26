/* 模拟支付（演示环境，不接入真实微信支付） */

/**
 * 弹出模拟收银台并假装支付。
 * @param {object} p {amount: number, title: string}
 * @returns {Promise<{tradeNo: string}>} 用户确认支付成功后 resolve
 */
function mockPay(p) {
  return new Promise((resolve, reject) => {
    wx.showModal({
      title: '模拟支付',
      content:
        '【演示环境】' +
        (p.title ? p.title + '，' : '') +
        '应付 ' +
        '¥' +
        (Math.round(Number(p.amount) * 100) / 100).toFixed(2) +
        '。不产生真实扣款，确认模拟支付？',
      confirmText: '确认支付',
      cancelText: '取消',
      success(res) {
        if (!res.confirm) {
          reject(new Error('用户取消支付'))
          return
        }
        // 假装走一次支付耗时，让流程更真实
        wx.showLoading({ title: '正在支付…', mask: true })
        setTimeout(() => {
          wx.hideLoading()
          resolve({ tradeNo: 'MOCK' + Date.now() })
        }, 900)
      },
      fail(err) {
        reject(new Error(err.errMsg || '支付弹窗失败'))
      },
    })
  })
}

module.exports = { mockPay }
