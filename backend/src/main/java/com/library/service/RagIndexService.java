package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.entity.RagDocument;
import com.library.entity.RagSegment;
import com.library.mapper.RagDocumentMapper;
import com.library.mapper.RagSegmentMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 索引服务：切分、向量化、落库与内存索引维护。
 * <p>
 * 存储结构：
 * <pre>
 * rag_segments（MySQL，唯一数据源：文本 + float32 向量）
 *        ↓ 启动时异步加载
 * InMemoryEmbeddingStore（进程内，检索用；万级片段暴力余弦 &lt; 50ms）
 * </pre>
 * 约定：
 * <ul>
 *   <li>store 的 id 用 segment 主键字符串，删除按 metadata {@code documentId} 过滤（无需 id 映射表）</li>
 *   <li>TextSegment metadata 必须带 {@code documentId} / {@code docTitle} / {@code scope}：
 *       前两者用于来源标注，{@code scope} 用于检索层权限隔离</li>
 *   <li>本地中文模型只能用<b>按字符</b>的 {@code recursive(chunkSize, overlap)} 切分，
 *       按 token 的重载需要与模型匹配的 tokenizer，用 OpenAiTokenizer 会导致切分长度失真</li>
 * </ul>
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    public static final String SCOPE_PUBLIC = "public";
    public static final String SCOPE_ADMIN = "admin";

    @Autowired
    private RagDocumentMapper documentMapper;
    @Autowired
    private RagSegmentMapper segmentMapper;
    @Autowired
    private LibraryConfigService configService;
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /** 本地 ONNX 中文 embedding 模型，懒加载（首次使用时初始化，避免拖慢应用启动） */
    private volatile EmbeddingModel embeddingModel;

    /** 内存索引；重建时构建新实例后整体替换，检索侧只读该 volatile 引用 */
    private volatile InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    /** 索引是否已加载就绪；加载完成前 RAG 视为不可用（AiService 判空跳过） */
    private volatile boolean ready = false;

    // ==================== 启动加载 ====================

    /**
     * 启动时异步加载全量启用片段到内存索引，避免拖慢应用启动。
     */
    @PostConstruct
    public void loadOnStartup() {
        taskExecutor.execute(this::loadFromDatabase);
    }

    private synchronized void loadFromDatabase() {
        try {
            List<RagSegment> segments = segmentMapper.selectAllEnabledWithEmbedding();
            int dim = model().dimension();
            InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
            int skipped = 0;
            for (RagSegment s : segments) {
                if (s.getEmbedding() == null || s.getEmbedding().length != dim * Float.BYTES) {
                    skipped++;
                    continue;
                }
                fresh.add(String.valueOf(s.getId()),
                        Embedding.from(toFloats(s.getEmbedding())),
                        toSegment(s.getDocumentId(), s.getDocTitle(), s.getDocScope(), s.getContent()));
            }
            store = fresh;
            log.info("[RAG] 内存索引加载完成：片段 {} 条，跳过维度不符 {} 条", fresh.size(), skipped);

            if (skipped > 0 || !embeddingSignatureMatches()) {
                log.warn("[RAG] 检测到 embedding 模型/维度与 rag_config 记录不一致，自动全量重建索引");
                rebuildAll();
            }
            ready = true;
        } catch (Exception e) {
            log.error("[RAG] 索引加载失败，本次运行 RAG 不可用（可稍后在知识库页手动重建）", e);
        }
    }

    // ==================== 文档入库 / 重建 / 删除 ====================

    /**
     * 新建文档并建立索引（原文同时落库，便于日后按新参数重建）。
     */
    public synchronized RagDocument createDocument(String title, String scope, String rawText) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "文档标题不能为空");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new BusinessException(400, "文档内容不能为空");
        }
        String normalizedScope = SCOPE_ADMIN.equals(scope) ? SCOPE_ADMIN : SCOPE_PUBLIC;
        LocalDateTime now = LocalDateTime.now();
        RagDocument doc = new RagDocument();
        doc.setTitle(title.trim());
        doc.setSourceType("manual");
        doc.setScope(normalizedScope);
        doc.setStatus(1);
        doc.setSegmentCount(0);
        doc.setRawText(rawText);
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentMapper.insert(doc);

        int count = indexDocument(doc);
        log.info("[RAG] 文档已入库：id={} title={} scope={} 片段 {} 条", doc.getId(), doc.getTitle(), normalizedScope, count);
        return doc;
    }

    /**
     * 重建单篇文档索引：先清旧片段（DB + store），再按当前参数重新切分入库。
     */
    public synchronized int indexDocument(RagDocument doc) {
        segmentMapper.deleteByDocumentId(doc.getId());
        InMemoryEmbeddingStore<TextSegment> current = store;
        if (current != null) {
            current.removeAll(metadataKey("documentId").isEqualTo(String.valueOf(doc.getId())));
        }
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        return indexInto(current, doc, cfg.getChunkSize(), cfg.getChunkOverlap());
    }

    /**
     * 按原文本重建某文档索引（依赖 {@code raw_text}，缺失时无法重建）。
     */
    public synchronized int reindex(Long docId) {
        RagDocument doc = requireDocument(docId);
        if (doc.getRawText() == null || doc.getRawText().isBlank()) {
            throw new BusinessException(400, "该文档未保存原文，无法重建索引，请删除后重新录入");
        }
        int count = indexDocument(doc);
        log.info("[RAG] 文档索引已重建：id={} 片段 {} 条", docId, count);
        return count;
    }

    /**
     * 删除文档及其全部片段，并移出内存索引。
     */
    public synchronized void remove(Long docId) {
        RagDocument doc = requireDocument(docId);
        segmentMapper.deleteByDocumentId(docId);
        InMemoryEmbeddingStore<TextSegment> current = store;
        if (current != null) {
            current.removeAll(metadataKey("documentId").isEqualTo(String.valueOf(docId)));
        }
        documentMapper.deleteById(docId);
        log.info("[RAG] 文档已删除：id={} title={}", docId, doc.getTitle());
    }

    /**
     * 全量重建索引：清空片段表与内存索引，按当前 chunkSize/chunkOverlap 重新切分全部启用文档。
     * 供切换参数、切换 embedding 模型后手动触发。
     */
    public synchronized int rebuildAll() {
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        List<RagDocument> docs = documentMapper.selectEnabled();
        InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
        segmentMapper.deleteAll();
        int total = 0;
        for (RagDocument doc : docs) {
            total += indexInto(fresh, doc, cfg.getChunkSize(), cfg.getChunkOverlap());
        }
        store = fresh;
        configService.updateRagEmbeddingInfo(LibraryConfigService.EMBEDDING_MODEL_NAME, model().dimension());
        ready = true;
        log.info("[RAG] 全量重建完成：文档 {} 篇，片段 {} 条", docs.size(), total);
        return total;
    }

    // ==================== 检索 ====================

    /**
     * 向量检索 Top N 片段。
     *
     * @param scope 非空时按 scope 过滤（读者传 public）；为空表示不过滤（管理员检索测试可用）
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, Integer maxResults, Double minScore, String scope) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(400, "检索内容不能为空");
        }
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(model().embed(query).content())
                .maxResults(maxResults != null ? maxResults : cfg.getMaxResults())
                .minScore(minScore != null ? minScore : cfg.getMinScore());
        if (scope != null && !scope.isBlank()) {
            builder.filter(metadataKey("scope").isEqualTo(scope));
        }
        return store.search(builder.build()).matches();
    }

    /** 索引是否已就绪（未就绪时调用方应跳过 RAG） */
    public boolean isReady() {
        return ready;
    }

    /** 内存索引片段数 */
    public int indexedSegmentCount() {
        return store.size();
    }

    /** 内存索引只读引用（供 AiService 构造 retriever） */
    public EmbeddingStore<TextSegment> getStore() {
        return store;
    }

    /** embedding 模型（供 AiService 构造 retriever） */
    public EmbeddingModel getEmbeddingModel() {
        return model();
    }

    // ==================== 内部实现 ====================

    /**
     * 切分 → 逐片向量化 → 写 rag_segments → 入内存索引。调用方负责先清理旧片段。
     *
     * @return 片段数
     */
    private int indexInto(InMemoryEmbeddingStore<TextSegment> target, RagDocument doc, int chunkSize, int chunkOverlap) {
        String text = doc.getRawText();
        if (text == null || text.isBlank()) {
            documentMapper.updateSegmentCount(doc.getId(), 0);
            return 0;
        }
        List<TextSegment> chunks = DocumentSplitters.recursive(chunkSize, chunkOverlap)
                .split(Document.from(text));
        int seq = 0;
        for (TextSegment chunk : chunks) {
            Embedding embedding = model().embed(chunk.text()).content();
            RagSegment seg = new RagSegment();
            seg.setDocumentId(doc.getId());
            seg.setSeq(seq++);
            seg.setContent(chunk.text());
            seg.setEmbedding(toBytes(embedding.vector()));
            seg.setCreateTime(LocalDateTime.now());
            segmentMapper.insert(seg);
            if (target != null) {
                target.add(String.valueOf(seg.getId()), embedding,
                        toSegment(doc.getId(), doc.getTitle(), doc.getScope(), chunk.text()));
            }
        }
        documentMapper.updateSegmentCount(doc.getId(), chunks.size());
        return chunks.size();
    }

    /** TextSegment metadata：documentId/docTitle 供来源标注，scope 供检索层权限隔离 */
    private TextSegment toSegment(Long documentId, String docTitle, String scope, String text) {
        Metadata metadata = Metadata.from(Map.of(
                "documentId", String.valueOf(documentId),
                "docTitle", docTitle == null ? "" : docTitle,
                "scope", scope == null ? SCOPE_PUBLIC : scope));
        return TextSegment.from(text, metadata);
    }

    private RagDocument requireDocument(Long docId) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        return doc;
    }

    /** 懒加载本地 ONNX 模型（首帧约 1s，放首次使用位置而非 Bean 构造期，避免拖慢启动） */
    private EmbeddingModel model() {
        EmbeddingModel m = embeddingModel;
        if (m == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
                    log.info("[RAG] 本地中文 embedding 模型已加载：{}（{} 维）",
                            LibraryConfigService.EMBEDDING_MODEL_NAME, embeddingModel.dimension());
                }
                m = embeddingModel;
            }
        }
        return m;
    }

    /** 建索引所用模型/维度是否与 rag_config 记录一致（不一致必须重建，否则新旧向量混算会静默劣化） */
    private boolean embeddingSignatureMatches() {
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        return LibraryConfigService.EMBEDDING_MODEL_NAME.equals(cfg.getEmbeddingModel())
                && cfg.getEmbeddingDim() == model().dimension();
    }

    /** float[] → float32 小端序字节（384 维 = 1536 字节） */
    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    private static float[] toFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}