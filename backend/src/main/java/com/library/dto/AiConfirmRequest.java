package com.library.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 写操作授权确认请求。
 * <p>
 * <b>只传令牌，不传参数</b>：参数保存在服务端（见 {@code AiConfirmService}），
 * 客户端无法篡改要执行的写操作内容——这是授权机制的关键安全设计。
 */
public class AiConfirmRequest {

    /** 授权令牌（由 confirm 事件下发） */
    @NotBlank(message = "缺少授权令牌")
    private String token;

    /** 待执行的工具名（用于校验令牌与目标操作是否匹配） */
    @NotBlank(message = "缺少操作标识")
    private String tool;

    /**
     * 当前会话 ID（可选）：与令牌签发时的会话比对，防止令牌跨会话重放。
     * 前端逐步接入；未传时跳过该校验（兼容旧客户端）。
     */
    private Long conversationId;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }
}
