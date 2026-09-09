package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 可调整 Prompt 段（人设/语气/称呼）保存请求。
 * <p>
 * 核心业务规则硬编码在 AiService 中，不受此内容影响。
 */
@Data
public class AiPromptRequest {

    @NotBlank(message = "人设内容不能为空")
    private String content;
}
