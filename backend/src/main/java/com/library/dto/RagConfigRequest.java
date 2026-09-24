package com.library.dto;

import lombok.Data;

/**
 * RAG 检索/切分参数更新请求。
 * <p>
 * 字段留空表示保持原值；{@code embeddingModel} / {@code embeddingDim} 不可由前端修改
 * （由后端在重建索引后回写）。
 */
@Data
public class RagConfigRequest {
    /** RAG 总开关 */
    private Boolean enabled;
    /** 检索返回片段数 */
    private Integer maxResults;
    /** 相似度下限 */
    private Double minScore;
    /** 切片字数 */
    private Integer chunkSize;
    /** 切片重叠字数 */
    private Integer chunkOverlap;
}