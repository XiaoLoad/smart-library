package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 确认请求
 */
@Data
public class AiConfirmRequest {

    /** 授权令牌（服务端签发，客户端只回传它） */
    @NotBlank(message = "缺少授权令牌")
    private String token;

    /** 期望执行的工具名（与令牌签发时比对，防拿 A 工具令牌执行 B 工具） */
    @NotBlank(message = "缺少操作标识")
    private String tool;

    /**
     * 当前会话 ID（可选）：与令牌签发时的会话比对，防止令牌跨会话重放。
     * 前端逐步接入；未传时跳过该校验（兼容旧客户端）。
     */
    private Long conversationId;

    /**
     * M8：是否同时勾选「本会话始终允许该操作」。
     * true 时确认成功后把该工具登记进会话白名单，后续同类写操作不再弹卡。
     */
    private Boolean alwaysAllow;
}
