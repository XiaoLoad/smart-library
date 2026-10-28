package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 对话请求
 */
@Data
public class AiChatRequest {
    @NotNull(message = "会话ID不能为空")
    private Long conversationId;

    /**
     * 消息内容：常规对话为用户输入；确认续跑（continuation=true）时为
     * 系统注入的引导语，落库标记为系统续跑而非用户发言。
     */
    @NotBlank(message = "消息内容不能为空")
    private String content;

    /**
     * M8 确认续跑标记：true 表示本轮由前端在授权确认成功后自动发起，
     * content 是系统注入的继续引导语。续跑消息不按用户发言展示，
     * 且受续跑次数上限约束（防确认循环）。
     */
    private Boolean continuation;
}
