/* ===== 封面通道自检 =====
 * 真机上封面不显示时，问题可能出在协议、域名白名单、局域网连通性任何一环。
 * 这一页把同一张封面分别交给四条通道渲染，各自接 load/error 事件打标，
 * 不必靠猜：哪条出图，方案就往哪条定。
 *
 * 四格缩略图长得一样，所以通道编号要显眼到不可能认错：大字字母块 + 状态徽标，
 * 末尾再由 conclude() 把四条判定收成一句话，省得逐格比对。
 *
 * 这是开发调试页，发布前可从 app.json 摘掉；「我的」里的入口也只在该页可达。
 */
const request = require('../../utils/request')
const cover = require('../../utils/cover')
const { ORIGIN } = require('../../utils/config')

/* 状态档：同一个 tone 同时决定徽标与字母块的配色，不再各写一份 */
const TONE = {
  wait: { st: '检测中…', badgeCls: 'badge-gray', letterCls: 'lt-wait' },
  ok: { st: '已出图', badgeCls: 'badge-green', letterCls: 'lt-ok' },
  fail: { st: '加载失败', badgeCls: 'badge-red', letterCls: 'lt-fail' },
  todo: { st: '请你看一眼', badgeCls: 'badge-gray', letterCls: 'lt-todo' },
  none: { st: '无样本', badgeCls: 'badge-gray', letterCls: 'lt-wait' },
}

function tone(ch, name, stOverride, msg) {
  const next = Object.assign({}, ch, TONE[name], { tone: name })
  if (stOverride) next.st = stOverride
  if (msg) next.hint = ch.hint + '｜' + msg
  return next
}

Page({
  data: {
    loading: true,
    title: '',
    localPath: '',
    localHttp: '',
    external: '',
    b64KB: '0',
    rawKB: '0',
    probe: '',
    channels: [],
    verdict: '',
    advice: '',
  },

  onLoad() {
    this.loadSample()
  },

  async loadSample() {
    let list = []
    try {
      const res = await request.get('/book/list', { page: 1, size: 50 }, { silent: true })
      list = res.list || []
    } catch (e) {
      this.setData({ loading: false, probe: '取样本失败：' + e.message })
      return
    }
    // 站内上传封面与第三方 https 外链都会用到，各取一张对照
    const local = list.filter((b) => (b.coverUrl || '').charAt(0) === '/')[0]
    const ext = list.filter((b) => /^https?:\/\//.test(b.coverUrl || ''))[0]
    if (!local) {
      this.setData({ loading: false, probe: '库中没有站内上传的封面可作样本' })
      return
    }

    const localHttp = ORIGIN + local.coverUrl
    this.setData({
      loading: false,
      title: local.title,
      localPath: local.coverUrl,
      localHttp,
      external: ext ? ext.coverUrl : '',
    })
    this.buildChannels(localHttp)
    this.loadSizes(local.coverUrl)
    this.probeLocal(localHttp)
  },

  buildChannels(localHttp) {
    const rows = [
      {
        key: 'A',
        name: 'image 组件 + 直连地址',
        hint: '现在的写法。开发者工具正常，真机上 http 会被微信客户端拦掉',
        kind: 'image',
        src: localHttp,
      },
      {
        key: 'B',
        name: 'CSS 背景图 + 直连地址',
        hint: '背景图由 WebView 渲染，不归小程序的图片资源校验管；它没有 load/error 事件，只能你来看',
        kind: 'view',
        src: localHttp,
      },
      {
        key: 'C',
        name: 'image 组件 + base64',
        hint: 'data URI 不经网络，绕开整条图片加载通道；代价是数据量',
        kind: 'image',
        src: '',
      },
      {
        key: 'D',
        name: 'image 组件 + 外链 https',
        hint: '第三方封面。https 不代表能显示，image 还认 downloadFile 合法域名',
        kind: 'image',
        src: this.data.external,
      },
    ]
    const channels = rows.map((r) => tone(r, r.key === 'B' ? 'todo' : r.src ? 'wait' : 'none'))
    this.setData({ channels })
    this.conclude()
  },

  /** 编码后与原始体积对照，说明 base64 通道的代价 */
  async loadSizes(path) {
    try {
      const [thumbRep, rawRep] = await Promise.all([
        cover.fetchRaw([path], cover.THUMB_WIDTH),
        cover.fetchRaw([path], 0),
      ])
      const b64 = (thumbRep && thumbRep[path]) || ''
      const raw = (rawRep && rawRep[path]) || ''
      this.setData({
        b64KB: (b64.length / 1024).toFixed(1),
        rawKB: (raw.length / 1024).toFixed(1),
        // 只补 C 通道的图源。整份重建会把已经判定完的 A、D 打回「检测中」
        'channels[2].src': b64,
      })
      this.conclude()
    } catch (e) {
      this.setData({ probe: '编码接口调用失败：' + e.message })
    }
  },

  /** 探针：直接问小程序图片通道能不能拿下这张图，拿到具体错误比看图猜有用 */
  probeLocal(src) {
    wx.getImageInfo({
      src,
      success: (r) =>
        this.setData({ probe: 'getImageInfo 成功：' + r.width + '×' + r.height + ' ' + (r.type || '') }),
      fail: (e) => this.setData({ probe: 'getImageInfo 失败：' + (e.errMsg || JSON.stringify(e)) }),
    })
  },

  onImgLoad(e) {
    this.setChannel(e.currentTarget.dataset.ch, 'ok')
  },
  onImgError(e) {
    this.setChannel(e.currentTarget.dataset.ch, 'fail', (e.detail && e.detail.errMsg) || 'load error')
  },
  /** msg 是失败原因（常见），stOverride 用来顶掉默认文案（只给人工判定用） */
  setChannel(key, name, msg, stOverride) {
    const channels = this.data.channels.map((c) => (c.key === key ? tone(c, name, stOverride, msg) : c))
    this.setData({ channels })
    this.conclude()
  },

  /** B 通道没有事件可接，交给使用者点一下补上判定 */
  onJudge(e) {
    const { ch, judge } = e.currentTarget.dataset
    this.setChannel(ch, judge, null, judge === 'ok' ? '已出图 · 你确认的' : '看不到 · 你确认的')
  },

  /**
   * 把四条判定收成两句话。判定不齐也不硬等——已判的如实报，未判的标出来，
   * 免得一面「马上就好」一面永远不出结论。
   */
  conclude() {
    const chs = this.data.channels
    if (!chs.length) {
      this.setData({ verdict: '', advice: '' })
      return
    }
    const pick = (t) => chs.filter((c) => c.tone === t).map((c) => c.key)
    const ok = pick('ok')
    const bad = pick('fail')
    const open = pick('todo').concat(pick('wait'))
    const none = pick('none')
    const seg = []
    if (ok.length) seg.push('能出图 ' + ok.join('、'))
    if (bad.length) seg.push('出不来 ' + bad.join('、'))
    if (open.length) seg.push('没判定 ' + open.join('、'))
    // 无样本的通道也要列出来，否则它从结论里凭空消失，看着像漏了一条
    if (none.length) seg.push('无样本 ' + none.join('、'))

    const keyOk = (k) => chs.filter((c) => c.key === k && c.tone === 'ok').length > 0
    const keyBad = (k) => chs.filter((c) => c.key === k && c.tone === 'fail').length > 0
    const tips = []
    if (keyOk('A')) {
      tips.push('A 出图说明这台真机根本没被协议拦住，直连本来就够用，base64 与背景图都是多余的，把 COVER_MODE 改回 direct 即可')
    } else if (keyBad('A')) {
      if (keyOk('C')) tips.push('A 不出而 C 出，正是「图片被加载通道拦下」的样子，base64 这条可走')
      if (keyBad('C')) tips.push('C 也不出图，先看上面「编码后」是不是 0——是 0 就是后端没重启，新接口还没上线')
    }
    if (keyOk('B')) {
      tips.push('背景图那条也出图，它不占数据量、不用限制翻页，比 base64 省')
    }
    if (!ok.length && !open.length) {
      tips.push('四条都不通，先用「复制直连地址」在手机浏览器里打开那个地址，打不开就是局域网或防火墙问题，与小程序无关')
    }
    this.setData({ verdict: seg.join(' · '), advice: tips.join('；') })
  },

  onCopyHttp() {
    wx.setClipboardData({ data: this.data.localHttp })
  },

  onRetest() {
    this.setData({ loading: true, probe: '', channels: [], verdict: '', advice: '' })
    this.loadSample()
  },
})
