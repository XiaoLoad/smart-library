package com.library.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库文档（rag_documents）。
 * <p>
 * 一篇文档被切分为若干 {@link RagSegment} 入库检索；
 * {@code rawText} 保存解析后的原文，用于按最新 chunkSize 重建索引。
 */
@Data
public class RagDocument {
    private Long id;
    private String title;
    /** manual=人工上传, book=书目同步（预留） */
    private String sourceType;
    /** 来源引用（如 book.id，预留） */
    private String sourceRef;
    /** public=读者可见, admin=仅管理员 */
    private String scope;
    /** 1=启用, 0=停用 */
    private Integer status;
    /** 片段数（冗余，便于列表展示） */
    private Integer segmentCount;
    /** 解析后的原文；列表查询不返回该字段（见 RagDocumentMapper.xml） */
    private String rawText;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}