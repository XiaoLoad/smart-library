package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import com.library.service.LibraryConfigService.AsrChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ASR（语音转文字）服务：把前端录音中转给云端转写服务商。
 * <p>
 * <b>中转而非直连</b>：API Key 只存在服务端 system_settings（asr_channels），
 * 前端永远接触不到；音频字节流在内存中转发，不落盘、不写日志（语音含用户 PII）。
 * <p>
 * <b>通用协议适配</b>：当前仅支持「HTTP multipart file + Bearer 鉴权 + 响应取
 * {@code text} 字段」的形态，覆盖 DeepSeek（/v1/tools/asr）、OpenAI / 硅基流动
 * （/v1/audio/transcriptions）等主流云端点。差异通过渠道配置消化：
 * DeepSeek 忽略多余的 model/language 表单字段，OpenAI 兼容端点按配置携带。
 * <p>
 * <b>上传限制</b>：按最严的服务商（DeepSeek：8MiB / 120 秒）统一执行，
 * WAV 按头部字节数率估算时长，非 WAV 格式仅做大小校验（前端录音固定产 WAV）。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    /** 单次上传音频上限：DeepSeek ASR 为 8MiB，取各服务商最严值 */
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    /** 音频时长上限：DeepSeek 为 120 秒，留 10 秒解析余量 */
    private static final int MAX_DURATION_MS = 130_000;
    /** 上游转写请求总超时（短语音场景 20s 足够，网络抖动留 5s 余量） */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(25);
    /** 连接建立超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 测试用静音 WAV 时长（毫秒） */
    private static final int TEST_WAV_DURATION_MS = 1000;
    /** 测试用 WAV 采样率（16kHz 单声道 16bit，与前端录音参数一致） */
    private static final int TEST_WAV_SAMPLE_RATE = 16000;
    /** 错误信息中截取上游响应体的最大长度（避免整页 HTML 刷屏） */
    private static final int ERROR_BODY_SNIPPET_LEN = 200;

    @Autowired
    private LibraryConfigService configService;
    @Autowired
    private ObjectMapper objectMapper;

    /** HttpClient 无状态可复用；连接池由 JDK 内部管理 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 转写当前启用渠道。
     *
     * @param audio    音频字节（前端固定上传 16kHz 单声道 16bit WAV）
     * @param filename 原始文件名（透传给上游 multipart，供格式判断）
     * @param language 语种提示（可空；DeepSeek 自动识别，OpenAI 兼容端点选填）
     * @return text=转写文本，durationMs=音频时长（上游返回或 WAV 头估算）
     */
    public Map<String, Object> transcribe(byte[] audio, String filename, String language) {
        AsrChannel channel = configService.getActiveAsrChannel();
        if (channel == null) {
            throw new BusinessException(400, "语音识别未配置或已停用，请联系管理员");
        }
        return doTranscribe(channel, audio, filename, language);
    }

    /**
     * 管理端连通性测试：向上游发送 1 秒静音 WAV 实测完整转写链路（鉴权/格式/网络）。
     * 比拉模型列表更真实——部分转写服务没有 models 端点可探测。
     *
     * @return ok、latencyMs、message
     */
    public Map<String, Object> testChannel(AsrChannel channel) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            doTranscribe(channel, silenceWav(), "connectivity-test.wav", null);
            result.put("ok", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", "连接正常，转写链路可用");
        } catch (BusinessException e) {
            result.put("ok", false);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.warn("[ASR] 渠道测试异常：endpoint={}", channel.getEndpoint(), e);
            result.put("ok", false);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", "测试失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 实际转写执行：校验 → 组装 multipart → 调上游 → 解析 text。
     */
    private Map<String, Object> doTranscribe(AsrChannel channel, byte[] audio, String filename, String language) {
        if (audio == null || audio.length == 0) {
            throw new BusinessException(400, "音频内容为空");
        }
        if (audio.length > MAX_UPLOAD_BYTES) {
            throw new BusinessException(400, "音频超过 8MB 上限，请缩短录音时长");
        }
        int wavDurationMs = parseWavDurationMs(audio);
        if (wavDurationMs > MAX_DURATION_MS) {
            throw new BusinessException(400, "音频超过 120 秒上限，请缩短录音时长");
        }

        long start = System.currentTimeMillis();
        String text = requestUpstream(channel, audio, filename, language);
        long costMs = System.currentTimeMillis() - start;
        log.info("[ASR] 转写完成：channel={} 音频 {}KB 耗时 {}ms 文本 {} 字",
                channel.getName(), audio.length / 1024, costMs, text == null ? 0 : text.length());

        Map<String, Object> result = new HashMap<>();
        result.put("text", text == null ? "" : text);
        result.put("durationMs", wavDurationMs > 0 ? wavDurationMs : -1);
        return result;
    }

    /**
     * 调用上游转写接口并返回 text 字段。
     * <p>
     * model / language 仅在配置非空时携带——DeepSeek 会忽略多余字段，
     * OpenAI 兼容端点按需读取，两类服务共用同一请求构造。
     */
    private String requestUpstream(AsrChannel channel, byte[] audio, String filename, String language) {
        String boundary = "----AsrBoundary" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> fields = new LinkedHashMap<>();
        if (channel.getModel() != null && !channel.getModel().isBlank()) {
            fields.put("model", channel.getModel().trim());
        }
        if (language != null && !language.isBlank()) {
            fields.put("language", language.trim());
        }
        byte[] body = buildMultipart(boundary, fields, audio,
                (filename == null || filename.isBlank()) ? "speech.wav" : filename);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(channel.getEndpoint()))
                    .timeout(CALL_TIMEOUT)
                    .header("Authorization", "Bearer " + channel.getApiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (Exception e) {
            throw new BusinessException(400, "转写接口地址无效：" + channel.getEndpoint());
        }

        HttpResponse<byte[]> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BusinessException(504, "转写服务响应超时，请稍后重试");
        } catch (Exception e) {
            log.warn("[ASR] 上游请求失败：endpoint={}", channel.getEndpoint(), e);
            throw new BusinessException(502, "无法连接转写服务，请检查网络或接口地址");
        }

        if (resp.statusCode() != 200) {
            throw mapUpstreamError(resp.statusCode(), resp.body());
        }
        return extractText(resp.body(), channel.getName());
    }

    /**
     * 上游非 200 响应 → 面向用户的业务异常。
     * 状态码语义对齐 DeepSeek ASR 文档（429/503 繁忙、413 超大、504 超时），
     * OpenAI 兼容端点的 401/422 等归入鉴权与参数两类。
     */
    private BusinessException mapUpstreamError(int status, byte[] bodyBytes) {
        String snippet = bodySnippet(bodyBytes);
        log.warn("[ASR] 上游返回非 200：status={} body={}", status, snippet);
        return switch (status) {
            case 401, 403 -> new BusinessException(502, "转写渠道鉴权失败，请检查 API Key");
            case 413 -> new BusinessException(400, "音频超过服务商大小上限，请缩短录音时长");
            case 429 -> new BusinessException(429, "转写服务繁忙，请稍后重试");
            case 503 -> new BusinessException(502, "转写服务暂时不可用，请稍后重试");
            case 504 -> new BusinessException(504, "转写超时，请稍后重试");
            default -> new BusinessException(502, "转写服务返回错误（HTTP " + status + "）：" + snippet);
        };
    }

    /**
     * 从成功响应中提取 text 字段（DeepSeek 与 OpenAI 兼容端点均为顶层 text）。
     */
    private String extractText(byte[] bodyBytes, String channelName) {
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            JsonNode text = root.get("text");
            if (text == null || text.isNull()) {
                throw new BusinessException(502, "转写服务响应缺少 text 字段，请检查渠道「"
                        + channelName + "」的接口地址是否为转写端点");
            }
            return text.asText("");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[ASR] 上游响应解析失败：{}", bodySnippet(bodyBytes));
            throw new BusinessException(502, "转写服务响应格式异常，无法解析转写文本");
        }
    }

    /**
     * 手工组装 multipart/form-data（JDK HttpClient 无内置 multipart，项目未引入
     * httpclient5 等依赖，字节数组拼装即可满足两个表单字段 + 一个文件的简单场景）。
     */
    private byte[] buildMultipart(String boundary, Map<String, String> fields,
                                  byte[] file, String filename) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 512);
            String prefix = "--" + boundary + "\r\n";
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.writeBytes(prefix.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.writeBytes(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes(prefix.getBytes(StandardCharsets.UTF_8));
            out.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.writeBytes("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes(file);
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(500, "组装上传请求失败：" + e.getMessage());
        }
    }

    /**
     * 解析标准 44 字节头 WAV 的时长（毫秒）；非 WAV / 头部异常返回 -1（跳过时长校验）。
     * 前端录音为固定 16kHz 单声道 16bit PCM，标准头结构可安全假设。
     */
    static int parseWavDurationMs(byte[] audio) {
        if (audio == null || audio.length < 44) {
            return -1;
        }
        // RIFF 头 + WAVE 标识 + fmt 块固定偏移：sampleRate@24，byteRate@28（小端）
        if (audio[0] != 'R' || audio[1] != 'I' || audio[2] != 'F' || audio[3] != 'F'
                || audio[8] != 'W' || audio[9] != 'A' || audio[10] != 'V' || audio[11] != 'E') {
            return -1;
        }
        long byteRate = (audio[28] & 0xFFL)
                | (audio[29] & 0xFFL) << 8
                | (audio[30] & 0xFFL) << 16
                | (audio[31] & 0xFFL) << 24;
        if (byteRate <= 0) {
            return -1;
        }
        long dataLen = audio.length - 44L;
        return (int) (dataLen * 1000L / byteRate);
    }

    /**
     * 生成 1 秒静音 WAV（16kHz 单声道 16bit PCM）：连通性测试的探测音频。
     * 上游对静音返回空文本属正常，测试只关心 HTTP 链路与鉴权是否打通。
     */
    static byte[] silenceWav() {
        int sampleCount = TEST_WAV_SAMPLE_RATE * TEST_WAV_DURATION_MS / 1000;
        int dataLen = sampleCount * 2;
        int totalLen = 44 + dataLen;
        byte[] wav = new byte[totalLen];
        // RIFF 块
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putIntLE(wav, 4, totalLen - 8);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        // fmt 块（PCM=1，单声道，16kHz，16bit）
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putIntLE(wav, 16, 16);
        putShortLE(wav, 20, (short) 1);
        putShortLE(wav, 22, (short) 1);
        putIntLE(wav, 24, TEST_WAV_SAMPLE_RATE);
        putIntLE(wav, 28, TEST_WAV_SAMPLE_RATE * 2);
        putShortLE(wav, 32, (short) 2);
        putShortLE(wav, 34, (short) 16);
        // data 块（静音 = 全 0，默认已初始化）
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putIntLE(wav, 40, dataLen);
        return wav;
    }

    private static void putIntLE(byte[] b, int offset, int value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void putShortLE(byte[] b, int offset, short value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static String bodySnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "(空响应)";
        }
        String s = new String(body, 0, Math.min(body.length, ERROR_BODY_SNIPPET_LEN * 4),
                StandardCharsets.UTF_8);
        return s.length() > ERROR_BODY_SNIPPET_LEN ? s.substring(0, ERROR_BODY_SNIPPET_LEN) + "…" : s;
    }
}
