// 借阅/罚款状态徽标
Component({
  properties: {
    status: { type: Number, value: 0 },
    type: { type: String, value: 'borrow' }, // borrow | fine
  },
  data: { label: '', cls: '' },
  observers: {
    'status, type': function (status, type) {
      const map =
        type === 'fine'
          ? {
              0: ['未缴', 'badge-red'],
              1: ['已缴', 'badge-green'],
            }
          : {
              0: ['待取书', 'badge-orange'],
              1: ['借阅中', 'badge-blue'],
              2: ['已归还', 'badge-green'],
              3: ['已逾期', 'badge-red'],
              4: ['已取消', 'badge-gray'],
              5: ['待确认归还', 'badge-info'],
            }
      const m = map[status] || ['未知', 'badge-gray']
      this.setData({ label: m[0], cls: m[1] })
    },
  },
})
