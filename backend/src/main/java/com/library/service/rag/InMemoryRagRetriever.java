package com.library.service.rag;

import com.library.service.LibraryConfigService;
import com.library.service.RagIndexService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存索引检索 Provider：当前 RAG 检索 seam 的默认实现。
 * <p>
 * 委托 {@link RagIndexService} 的进程内向量索引完成检索；索引未就绪时返回空结果
 * （fail-safe：调用方按「资料不足」降级，而非报错中断对话）。
 * truncated 语义：实现按 maxResults 截断是检索协议本身，不视为「结果不完整」；
 * 只有索引未就绪（应返回更多但无法返回）才标记 truncated=true。
 */
@Component
@Primary
public class InMemoryRagRetriever implements RagRetriever {

    @Autowired
    private RagIndexService ragIndexService;

    @Autowired
    private LibraryConfigService libraryConfigService;

    @Override
    public RagSearchResult retrieve(String query, int maxResults, double minScore, String scope) {
        if (query == null || query.isBlank() || !ragIndexService.isReady()) {
            return new RagSearchResult(List.of(), !ragIndexService.isReady());
        }
        LibraryConfigService.RagConfig cfg = libraryConfigService.getRagConfig();
        int limit = maxResults > 0 ? maxResults : cfg.getMaxResults();
        double threshold = minScore >= 0 ? minScore : cfg.getMinScore();
        List<EmbeddingMatch<TextSegment>> matches =
                ragIndexService.search(query, limit, threshold, scope);
        List<RagSource> sources = new ArrayList<>(matches.size());
        int no = 0;
        for (EmbeddingMatch<TextSegment> m : matches) {
            String title = m.embedded().metadata() == null
                    ? null : m.embedded().metadata().getString("docTitle");
            sources.add(RagSource.of(++no, title, m.embedded().text(), m.score()));
        }
        return new RagSearchResult(sources, false);
    }
}
