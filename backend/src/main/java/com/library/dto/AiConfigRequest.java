package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 接口配置请求
 */
@Data
public class AiConfigRequest {
    @NotBlank(message = "服务商不能为空")
    private String provider;
    private String apiKey;
    @NotBlank(message = "模型不能为空")
    private String model;
    private String endpoint;
}
