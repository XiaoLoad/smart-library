const request = require('../../utils/request')

Page({
  data: { oldPassword: '', newPassword: '', confirmPassword: '', saving: false },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value })
  },

  async onSubmit() {
    const { oldPassword, newPassword, confirmPassword } = this.data
    if (!oldPassword || !newPassword || !confirmPassword) {
      wx.showToast({ title: '请填写完整', icon: 'none' })
      return
    }
    if (newPassword.length < 6) {
      wx.showToast({ title: '新密码至少 6 位', icon: 'none' })
      return
    }
    if (newPassword !== confirmPassword) {
      wx.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      await request.post('/system/change-password', { oldPassword, newPassword })
      wx.showToast({ title: '修改成功', icon: 'success' })
      this.setData({ oldPassword: '', newPassword: '', confirmPassword: '' })
      setTimeout(() => wx.navigateBack(), 800)
    } catch (e) {
      /* 旧密码错误等 toast 已处理 */
    } finally {
      this.setData({ saving: false })
    }
  },
})
