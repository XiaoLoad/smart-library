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
    /**
     * 可见性：visible=正常渲染；hidden=系统机制消息（[系统回执]/[系统续跑]），
     * 仅落库供模型续跑上下文，前端不渲染（对齐 ZCode 的 internal 消息不进 UI）
     */
    private String visibility;
    /**
     * A2：同轮分支分组标识（编辑重发/重新生成产生的兄弟轮共享同一 key）。
     * 首轮为 NULL；同 key 的 user 消息互为兄弟分支，前端按 key 聚合出分支导航。
     */
    private String groupKey;
    /** A2：分支激活态。1=当前激活（历史接口返回）；0=被切走的分支（数据保留） */
    private Integer active;
    private LocalDateTime createTime;
}
