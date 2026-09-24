const request = require('../../utils/core/request')

Page({
  data: { form: { email: '', phone: '' }, user: null, saving: false, loading: true },

  onLoad() {
    this.load()
  },

  async load() {
    try {
      const user = await request.get('/user/profile')
      this.setData({
        user,
        loading: false,
        form: { email: user.email || '', phone: user.phone || '' },
      })
    } catch (e) {
      this.setData({ loading: false })
    }
  },

  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  async onSave() {
    const { email, phone } = this.data.form
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      wx.showToast({ title: '邮箱格式不正确', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      await request.post('/user/profile/update', { email, phone })
      wx.showToast({ title: '保存成功', icon: 'success' })
    } catch (e) {
      /* toast 已处理 */
    } finally {
      this.setData({ saving: false })
    }
  },
})
