package com.library.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图书向量（book_embeddings）。
 * <p>
 * 每本启用状态的图书对应一行：参与向量化的拼接文本 + float32 小端序向量。
 * 仅供智能检索的语义召回使用，与 RAG 知识库（rag_segments）相互独立。
 */
@Data
public class BookEmbedding {
    private Long id;
    private Long bookId;
    /** 参与向量化的拼接文本（书名+作者+出版社+分类+简介），用于重建索引与排查 */
    private String content;
    /** 向量：float32 小端序；列表查询不返回该字段 */
    private byte[] embedding;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 非表字段：加载/召回时由 books 关联带出，供检索结果直接展示
    private String title;
    private String author;
    private String coverUrl;
    private Integer availableCount;
    private Integer totalCount;
}
