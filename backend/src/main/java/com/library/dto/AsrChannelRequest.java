package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ASR（语音识别）渠道新增/编辑请求。
 * <p>
 * 与 {@link AiChannelRequest} 同构；编辑时 apiKey 留空表示保留原 Key 不修改。
 * model 为可选项：DeepSeek 等服务商由服务端自动识别语种/模型，可留空；
 * OpenAI 兼容端点（/v1/audio/transcriptions）按服务商要求填写。
 */
@Data
public class AsrChannelRequest {
    /** 渠道 ID（编辑时必传） */
    private Long id;
    /** 渠道名称（便于管理员辨认，如「DeepSeek 语音渠道」） */
    @NotBlank(message = "渠道名称不能为空")
    private String name;
    /** 转写接口完整地址（如 https://api.deepseek.com/v1/tools/asr） */
    @NotBlank(message = "接口地址不能为空")
    private String endpoint;
    /** API Key；新增时必填，编辑时留空表示不变 */
    private String apiKey;
    /** 模型名（OpenAI 兼容端点需要，如 FunAudioLLM/SenseVoiceSmall）；可留空 */
    private String model;
    /** 是否启用（新增时默认启用） */
    private Boolean enabled;
}
