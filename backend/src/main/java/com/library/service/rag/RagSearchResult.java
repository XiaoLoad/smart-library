package com.library.service.rag;

import java.util.List;

/**
 * RAG 检索结果（一次检索调用的完整产出）。
 * <p>
 * {@code truncated} 是 harness 借鉴的关键标志：检索实现因任何原因（maxResults 截断、
 * 外部源分页上限、超时部分返回）没有给出完整匹配集时必须置 true，
 * 下游（注入模板/前端展示）据此区分「就这些结果」与「还有更多未展示」，
 * 永不把部分结果当完整结果呈现。
 *
 * @param sources   命中的来源片段（已按相似度降序）
 * @param truncated 结果集是否被截断（true 时下游不得宣称「已检索全部资料」）
 */
public record RagSearchResult(List<RagSource> sources, boolean truncated) {

    /** 空结果（未命中或索引未就绪） */
    public static RagSearchResult empty() {
        return new RagSearchResult(List.of(), false);
    }

    public boolean isEmpty() {
        return sources == null || sources.isEmpty();
    }
}
