package com.library.service.rag;

/**
 * RAG 检索 seam（借鉴 deepseek-harness 的能力接口三角色：定义 / Provider / 消费者）。
 * <p>
 * <b>定义</b>：本接口；<b>当前 Provider</b>：{@link InMemoryRagRetriever}（本地 ONNX 向量 +
 * 进程内内存索引）；<b>消费者</b>：AiService（对话增强检索）与 RagController（检索测试）。
 * <p>
 * 未来更换检索实现（如 ES hybrid 检索、独立向量库）只需提供新的 Provider Bean 并
 * 标注 {@code @Primary}，消费者代码零改动。检索层权限（scope 过滤）由实现方负责。
 */
public interface RagRetriever {

    /**
     * 检索知识库片段。
     *
     * @param query      查询文本（非空）
     * @param maxResults 最多返回条数
     * @param minScore   相似度下限
     * @param scope      检索范围：public=仅公开资料；admin=公开+内部；null=不过滤（检索测试）
     * @return 检索结果（未就绪/未命中返回空结果，不抛异常——调用方按空结果降级）
     */
    RagSearchResult retrieve(String query, int maxResults, double minScore, String scope);
}
