package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 渠道新增/编辑请求
 * <p>
 * 编辑时 apiKey 留空表示保留原 Key 不修改。
 */
@Data
public class AiChannelRequest {
    /** 渠道 ID（编辑时必传） */
    private Long id;
    /** 渠道名称（便于管理员辨认，如「DeepSeek 主渠道」） */
    @NotBlank(message = "渠道名称不能为空")
    private String name;
    /** 服务商类型（deepseek/openai/qwen/.../custom），仅用于默认地址推导与展示 */
    private String provider;
    /** API Key；新增时必填，编辑时留空表示不变 */
    private String apiKey;
    /** 模型名（从服务商远程拉取或手动输入） */
    @NotBlank(message = "模型不能为空")
    private String model;
    /** 接口地址（OpenAI 兼容 chat/completions 地址或其 baseUrl） */
    @NotBlank(message = "接口地址不能为空")
    private String endpoint;
    /** 是否启用（新增时默认启用） */
    private Boolean enabled;
}
