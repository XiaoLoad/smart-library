package com.library.service.rag;

/**
 * RAG 检索来源片段（面向展示与 prompt 的统一视图）。
 * <p>
 * seam 化后检索实现（当前为内存向量索引，未来可换 ES/外部向量库）
 * 统一产出本模型，AiService 的 rag SSE 事件与注入模板都以此为唯一数据源，
 * 下游不再感知具体检索实现。
 *
 * @param sourceNo 引用编号（从 1 开始，同一轮内唯一；前端角标与 prompt 引用约定共用）
 * @param docTitle 文档标题（缺失/空白时由工厂方法兜底为「未命名文档」）
 * @param snippet  片段摘要（已截断到展示安全长度）
 * @param score    相似度分数（0~1，检索实现给出；实现不暴露分数时为 null）
 */
public record RagSource(int sourceNo, String docTitle, String snippet, Double score) {

    /** 单个片段摘要的最大字符数（截断防止单条来源占满 prompt/前端展示区） */
    public static final int SNIPPET_MAX_LEN = 120;

    /** 带分数构造（检索测试等需要展示相似度的场景） */
    public static RagSource of(int sourceNo, String docTitle, String rawSnippet, double score) {
        return new RagSource(sourceNo, normalizeTitle(docTitle), truncate(rawSnippet, SNIPPET_MAX_LEN), score);
    }

    /** 无分数构造（对话链路 rag 事件，前端不展示分数） */
    public static RagSource of(int sourceNo, String docTitle, String rawSnippet) {
        return new RagSource(sourceNo, normalizeTitle(docTitle), truncate(rawSnippet, SNIPPET_MAX_LEN), null);
    }

    /** 标题规范化：null/空白兜底为「未命名文档」 */
    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "未命名文档" : title;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
