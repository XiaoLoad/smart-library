package com.library.controller;

import com.library.common.Result;
import com.library.service.AsrService;
import com.library.service.LibraryConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 助手语音输入（ASR）控制器。
 * <p>
 * 链路：前端录音（16kHz 单声道 WAV）→ 本控制器中转 → 云端转写服务商
 * （Key 只存服务端，前端不可见）→ 返回文本回填聊天输入框。
 * <p>
 * 音频为用户语音（PII），仅在内存中转发：不落盘、不记录内容日志。
 * 所有接口均需登录（JwtInterceptor 全局拦截），无需管理员身份。
 */
@RestController
@RequestMapping("/ai/asr")
public class AsrController {

    private static final Logger log = LoggerFactory.getLogger(AsrController.class);

    @Autowired
    private AsrService asrService;

    @Autowired
    private LibraryConfigService configService;

    /**
     * 语音转文字。multipart 上传 WAV 音频，服务端校验大小/时长后中转云端转写。
     *
     * @param file     音频文件（WAV；≤8MB、≤120s，由 AsrService 按最严服务商标准校验）
     * @param language 语种提示（可空；DeepSeek 自动识别，OpenAI 兼容端点选填 ISO-639-1）
     */
    @PostMapping("/transcribe")
    public Result<Map<String, Object>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传音频文件");
        }
        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (Exception e) {
            log.warn("[ASR] 读取上传音频失败", e);
            return Result.error(400, "音频读取失败，请重新录音");
        }
        Map<String, Object> data = asrService.transcribe(audio, file.getOriginalFilename(), language);
        return Result.success(data);
    }

    /**
     * 语音功能状态：是否存在启用中的 ASR 渠道。
     * 前端据此控制麦克风按钮显示（未配置时隐藏而非置灰，避免读者误以为故障）。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("available", configService.getActiveAsrChannel() != null);
        return Result.success(data);
    }
}
