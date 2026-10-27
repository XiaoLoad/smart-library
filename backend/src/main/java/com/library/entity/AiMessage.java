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
    /**
     * 工具快照数组：JSON（[{tool,type,data,createTime}]，仅含映射到可视化 type 的工具）。
     * 查询时点数据，前端据此重建历史消息的内联生成式卡片，并对历史卡片标注生成时间。
     */
    private String toolSnapshots;
    private LocalDateTime createTime;
}
