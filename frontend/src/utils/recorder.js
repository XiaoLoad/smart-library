/**
 * 麦克风录音器：getUserMedia 采集 PCM → 停止时编码 16bit 单声道 WAV。
 *
 * 为什么不用 MediaRecorder：浏览器默认产出 webm/opus 容器，而云端 ASR
 * （DeepSeek /v1/tools/asr 仅收 WAV/Ogg Opus）普遍不支持 webm；
 * WAV 是各转写服务商的通用格式，且 16kHz 单声道 60s 仅约 1.9MB。
 *
 * 采样率策略：尝试创建 16kHz AudioContext（Chrome/Edge/Firefox 支持自动重采样）；
 * 若浏览器忽略该参数则按实际采样率写 WAV 头——主流服务商 WAV 端点接受 8k~48k，
 * 无需自行实现重采样。AudioContext 由浏览器在标签页后台/挂起时自动限流，
 * 组件卸载时务必调用 cancel() 释放麦克风。
 */
export class MicRecorder {
  /**
   * @param {object} [options]
   * @param {number} [options.maxDurationMs] 最长录音时长（默认 120000，对齐云端 ASR 上限）
   * @param {(result: {blob: Blob, durationMs: number}) => void} [options.onMaxDuration]
   *        达到最长时长自动停止时的回调（与手动 stop() 返回同一结构）
   */
  constructor({ maxDurationMs = 120000, onMaxDuration } = {}) {
    this.maxDurationMs = maxDurationMs
    this.onMaxDuration = onMaxDuration
    this.recording = false
    this.stream = null
    this.ctx = null
    this.processor = null
    this.source = null
    this.chunks = []
    this.sampleRate = 16000
    this.startedAt = 0
    this.maxTimer = null
  }

  /** 开始录音。麦克风拒绝/无设备时抛出带用户可读文案的异常 */
  async start() {
    if (this.recording) return
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error('当前浏览器不支持录音（需 HTTPS 或 localhost 环境）')
    }
    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      })
    } catch (e) {
      if (e?.name === 'NotAllowedError') throw new Error('麦克风权限被拒绝，请在浏览器设置中允许后重试')
      if (e?.name === 'NotFoundError') throw new Error('未检测到可用麦克风设备')
      throw new Error('麦克风启动失败：' + (e?.message || e?.name || '未知错误'))
    }
    this.stream = stream
    this.ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 })
    this.sampleRate = this.ctx.sampleRate
    this.chunks = []
    this.source = this.ctx.createMediaStreamSource(stream)
    // ScriptProcessorNode 虽已标记废弃，但无需额外 worklet 模块文件，单文件即可跨浏览器工作；
    // 缓冲区 4096 帧（16kHz 下约 256ms），CPU 占用可忽略
    this.processor = this.ctx.createScriptProcessor(4096, 1, 1)
    this.processor.onaudioprocess = (e) => {
      // 复制一份：底层缓冲区会被复用
      this.chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)))
    }
    this.source.connect(this.processor)
    this.processor.connect(this.ctx.destination)
    this.recording = true
    this.startedAt = Date.now()
    this.maxTimer = setTimeout(() => {
      if (!this.recording) return
      const result = this._finish()
      this.onMaxDuration?.(result)
    }, this.maxDurationMs)
  }

  /** 停止并返回 WAV；未在录音中返回 null */
  async stop() {
    if (!this.recording) return null
    return this._finish()
  }

  /** 取消录音：丢弃数据并释放麦克风，不产生 WAV */
  cancel() {
    if (!this.recording) return
    this.recording = false
    clearTimeout(this.maxTimer)
    this.chunks = []
    this._release()
  }

  _finish() {
    this.recording = false
    clearTimeout(this.maxTimer)
    const durationMs = Date.now() - this.startedAt
    const pcm = this.chunks
    this.chunks = []
    this._release()
    const blob = this._encodeWav(pcm, this.sampleRate)
    return { blob, durationMs }
  }

  _release() {
    try { this.processor?.disconnect() } catch { /* 已断开 */ }
    try { this.source?.disconnect() } catch { /* 已断开 */ }
    this.stream?.getTracks().forEach((t) => t.stop())
    try { this.ctx?.close() } catch { /* 已关闭 */ }
    this.processor = null
    this.source = null
    this.stream = null
    this.ctx = null
  }

  /**
   * Float32 PCM 分片 → 16bit PCM WAV Blob。
   * 头部为标准 44 字节结构，与服务端 AsrService.parseWavDurationMs 的解析约定一致。
   */
  _encodeWav(chunks, sampleRate) {
    const totalSamples = chunks.reduce((n, c) => n + c.length, 0)
    const buffer = new ArrayBuffer(44 + totalSamples * 2)
    const view = new DataView(buffer)
    const writeStr = (offset, s) => {
      for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i))
    }
    writeStr(0, 'RIFF')
    view.setUint32(4, 36 + totalSamples * 2, true)
    writeStr(8, 'WAVE')
    writeStr(12, 'fmt ')
    view.setUint32(16, 16, true)          // fmt 块长度
    view.setUint16(20, 1, true)           // PCM
    view.setUint16(22, 1, true)           // 单声道
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, sampleRate * 2, true) // byteRate = sampleRate * 2字节
    view.setUint16(32, 2, true)           // 块对齐
    view.setUint16(34, 16, true)          // 位深
    writeStr(36, 'data')
    view.setUint32(40, totalSamples * 2, true)
    // 逐采样饱和截断：Float32 [-1,1] → Int16；长度对齐后写入
    let offset = 44
    for (const chunk of chunks) {
      for (let i = 0; i < chunk.length; i++, offset += 2) {
        const s = Math.max(-1, Math.min(1, chunk[i]))
        view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true)
      }
    }
    return new Blob([buffer], { type: 'audio/wav' })
  }
}
