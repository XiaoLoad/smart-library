package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiMessage {
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    /** 工具调用摘要：JSON 数组、按调用顺序记录工具名（如 ["queryMyBorrows","queryBooks"]） */
    private String toolSummary;
    private LocalDateTime createTime;
}
