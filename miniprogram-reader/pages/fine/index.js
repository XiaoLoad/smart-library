const request = require('../../utils/request')
const { mockPay } = require('../../utils/mock-pay')
const { fmtDateTime, fmtMoney } = require('../../utils/datetime')

const TABS = [
  { value: -1, label: '全部' },
  { value: 0, label: '未缴' },
  { value: 1, label: '已缴' },
]

Page({
  data: { tabs: TABS, current: -1, all: [], list: [], payingId: 0, loading: true },

  onLoad() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await request.get('/fine/list')
      const all = (res.list || []).map((f) => ({
        ...f,
        amountText: fmtMoney(f.amount),
        createText: fmtDateTime(f.createTime),
        paidText: fmtDateTime(f.paidTime),
      }))
      this.setData({ all })
      this.filter()
    } catch (e) {
      /* toast 已处理 */
    } finally {
      this.setData({ loading: false })
    }
  },

  filter() {
    const { all, current } = this.data
    this.setData({ list: current === -1 ? all : all.filter((f) => f.status === current) })
  },

  onTab(e) {
    this.setData({ current: Number(e.currentTarget.dataset.value) })
    this.filter()
  },

  // 模拟缴费：确认弹窗 → 假装支付 → 调用后端缴费登记
  onPay(e) {
    const id = Number(e.currentTarget.dataset.id)
    const fine = this.data.all.find((f) => f.id === id)
    if (!fine) return
    mockPay({ amount: fine.amount, title: '《' + fine.bookTitle + '》逾期罚款' })
      .then(async () => {
        this.setData({ payingId: id })
        try {
          await request.post('/fine/pay', { id })
          wx.showToast({ title: '缴费登记成功', icon: 'success' })
          await this.load()
        } catch (err) {
          /* toast 已处理 */
        } finally {
          this.setData({ payingId: 0 })
        }
      })
      .catch(() => {})
  },
})
