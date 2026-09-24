package com.library.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库切片（rag_segments）。
 * <p>
 * {@code embedding} 为 float32 小端序字节（512 维 = 2048 字节）。
 */
@Data
public class RagSegment {
    private Long id;
    private Long documentId;
    /** 片段序号，从 0 开始 */
    private Integer seq;
    /** 片段原文 */
    private String content;
    /** 向量：float32 小端序；列表查询不返回该字段 */
    private byte[] embedding;
    private LocalDateTime createTime;

    // ===== 以下为非表字段：检索/加载时由 rag_documents 关联带出，用于拼 TextSegment metadata =====
    private String docTitle;
    private String docScope;
}