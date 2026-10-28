package com.library.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批量授权确认请求（M7，batch 档位）。
 * <p>
 * 客户端只回传批次令牌与会话 ID；全部工具参数取自服务端保存的批次副本，
 * 与单笔授权一致，不存在参数被篡改的面。
 */
public class AiBatchConfirmRequest {

    /** 批次令牌 */
    @NotBlank(message = "缺少授权令牌")
    private String token;

    /** 当前会话 ID（与批次登记时的会话比对，防跨会话重放） */
    private Long conversationId;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }
}
