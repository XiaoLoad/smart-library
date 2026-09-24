/* ===== SSE 流式解析（配合 wx.request enableChunked） =====
 * 后端帧格式（SseEmitter）：`event: <type>\ndata: <payload>\n\n`
 * 事件类型（与 AiService 对齐）：
 *   thinking 思维链增量 / delta 正文增量 / reset 清空已输出正文（工具开始执行）
 *   final 消毒后的完整正文（整体替换已拼接内容）/ tool 工具调用过程（JSON）
 *   done 本轮结束 / error 错误消息
 */

/** UTF-8 增量解码：处理跨 chunk 切断的多字节字符 */
function createUtf8Decoder() {
  let pending = []
  function decodeChunk(u8) {
    const bytes = pending.length ? pending.concat(Array.from(u8)) : Array.from(u8)
    pending = []
    // 由尾向前最多回扫 3 个字节，定位最后一个多字节序列的首字节。
    // 纯续字节要先跳过再判断，否则「半个汉字」会被当成完整数据解掉并静默丢失。
    let end = bytes.length
    const scanFrom = Math.max(0, bytes.length - 3)
    for (let i = bytes.length - 1; i >= scanFrom; i--) {
      const b = bytes[i]
      if ((b & 0xc0) === 0x80) continue
      const need = (b & 0xf8) === 0xf0 ? 4 : (b & 0xf0) === 0xe0 ? 3 : (b & 0xe0) === 0xc0 ? 2 : 1
      if (bytes.length - i < need) end = i
      break
    }
    pending = bytes.slice(end)
    return codePointsToString(decodeUtf8Bytes(bytes.slice(0, end)))
  }
  return { decode: decodeChunk }
}

// fromCodePoint.apply 有实参个数上限，超长 chunk 需分段拼接
function codePointsToString(cps) {
  if (!cps.length) return ''
  const STEP = 4096
  if (cps.length <= STEP) return String.fromCodePoint.apply(null, cps)
  let out = ''
  for (let i = 0; i < cps.length; i += STEP) {
    out += String.fromCodePoint.apply(null, cps.slice(i, i + STEP))
  }
  return out
}

// 简易 UTF-8 字节流 → 码点数组
function decodeUtf8Bytes(bytes) {
  const out = []
  let i = 0
  while (i < bytes.length) {
    const b = bytes[i]
    let cp = 0
    let extra = 0
    if (b < 0x80) {
      cp = b
    } else if ((b & 0xe0) === 0xc0) {
      cp = b & 0x1f
      extra = 1
    } else if ((b & 0xf0) === 0xe0) {
      cp = b & 0x0f
      extra = 2
    } else if ((b & 0xf8) === 0xf0) {
      cp = b & 0x07
      extra = 3
    } else {
      i++
      continue
    }
    if (extra > 0 && i + extra > bytes.length - 1) break
    for (let k = 1; k <= extra; k++) cp = (cp << 6) | (bytes[i + k] & 0x3f)
    out.push(cp)
    i += extra + 1
  }
  return out
}

/** SSE 帧解析器：push 增量文本，onEvent({event, data}) 回调 */
function createSseParser(onEvent) {
  let buffer = ''
  return {
    push(text) {
      buffer += text
      let idx
      while ((idx = buffer.search(/\r?\n\r?\n/)) !== -1) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + buffer.match(/\r?\n\r?\n/)[0].length)
        const parsed = parseFrame(frame)
        if (parsed) onEvent(parsed)
      }
    },
    flush() {
      const parsed = parseFrame(buffer)
      buffer = ''
      if (parsed) onEvent(parsed)
    },
  }
}

function parseFrame(frame) {
  if (!frame || !frame.trim()) return null
  let event = 'message'
  const dataParts = []
  frame.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) {
      let d = line.slice(5)
      if (d.startsWith(' ')) d = d.slice(1)
      dataParts.push(d)
    }
  })
  // SSE 规范：data 为空则不派发事件。这里必须返回 null，否则后端直接回 JSON 时
  // 会被伪造成一个 event=message 的空帧，冲掉调用方「一个真帧都没收到」的判断。
  if (!dataParts.length) return null
  return { event, data: dataParts.join('\n') }
}

/**
 * 发起流式 AI 对话请求。
 * @param {object} p {url, data, onEvent, onDone, onError, onNonSse, onFallback}
 *   onNonSse  收到了内容但一个 SSE 帧都没解析出来（后端返回 JSON，如鉴权失败）
 *   onFallback 基础库过低、没有 onChunkReceived
 * @returns requestTask（可 abort）
 */
function streamPost(p) {
  const auth = require('./auth')
  const { BASE_URL } = require('./config')
  const decoder = createUtf8Decoder()
  let eventCount = 0
  let rawHead = ''
  const parser = createSseParser((evt) => {
    eventCount++
    p.onEvent(evt)
  })

  const task = wx.request({
    url: BASE_URL + p.url,
    method: 'POST',
    header: {
      Authorization: 'Bearer ' + auth.getToken(),
      'Content-Type': 'application/json',
    },
    data: p.data,
    enableChunked: true,
    // chunked 与 HTTP/2 不兼容，不显式关掉在部分真机上收不到分块
    enableHttp2: false,
    responseType: 'text',
    success(res) {
      // 基础库过低时没有 onChunkReceived，整段响应会在这一次性到达
      if (eventCount === 0 && typeof res.data === 'string' && res.data.indexOf('event:') !== -1) {
        parser.push(res.data)
      }
      parser.flush()
      // 收到了内容却一个 SSE 帧都没有：后端返回的是 JSON 响应（最常见是鉴权 401），
      // 既不能当正常结束，也不能静默——交给调用方按 body.code 处理
      if (eventCount === 0 && rawHead.trim()) {
        p.onNonSse && p.onNonSse(rawHead)
        return
      }
      p.onDone && p.onDone()
    },
    fail(err) {
      p.onError && p.onError(new Error(err.errMsg || '流式请求失败'))
    },
  })

  if (task && task.onChunkReceived) {
    task.onChunkReceived((res) => {
      const text = decoder.decode(new Uint8Array(res.data))
      if (!text) return
      if (rawHead.length < 2048) rawHead += text
      parser.push(text)
    })
  } else {
    p.onFallback && p.onFallback()
  }
  return task
}

module.exports = { createSseParser, createUtf8Decoder, streamPost }
