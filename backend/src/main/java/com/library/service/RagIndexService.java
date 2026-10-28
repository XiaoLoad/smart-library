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
import java.util.ArrayList;
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

    // 启动加载

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
            int added = addAll(fresh, segments, dim);
            store = fresh;
            int skipped = segments.size() - added;
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

    // 文档入库 / 重建 / 删除

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
        // 显式校验而非静默降级：拼写错误的 scope 会造成读者/管理员权限误解
        String normalizedScope;
        if (SCOPE_ADMIN.equals(scope)) {
            normalizedScope = SCOPE_ADMIN;
        } else if (SCOPE_PUBLIC.equals(scope)) {
            normalizedScope = SCOPE_PUBLIC;
        } else {
            throw new BusinessException(400, "scope 仅支持 admin/public");
        }
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
     * 重建单篇文档索引：先在局部草稿中完成切分与嵌入（不落库、不动活跃索引），
     * 成功后在紧凑序列里完成换装——DB 删旧写新，活跃索引该文档旧向量整体移除、新向量整体加入。
     * 切分/嵌入中途失败时 DB 与活跃索引旧数据原封不动；换装阶段两次索引操作之间没有嵌入等慢步骤，
     * 窗口内最多短暂查不到该篇，绝不出现新旧混杂的半篇中间态。
     */
    public synchronized int indexDocument(RagDocument doc) {
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        InMemoryEmbeddingStore<TextSegment> current = store;
        List<SegmentDraft> drafts = splitAndEmbed(doc, cfg.getChunkSize(), cfg.getChunkOverlap());
        // DB 侧先清后写，两步之间失败留下的只有「旧片段已删」状态，索引不受影响
        segmentMapper.deleteByDocumentId(doc.getId());
        List<String> ids = new ArrayList<>(drafts.size());
        List<Embedding> embeddings = new ArrayList<>(drafts.size());
        List<TextSegment> segments = new ArrayList<>(drafts.size());
        int seq = 0;
        for (SegmentDraft draft : drafts) {
            RagSegment seg = persistSegment(doc, seq++, draft, null);
            ids.add(String.valueOf(seg.getId()));
            embeddings.add(draft.embedding());
            segments.add(toSegment(doc.getId(), doc.getTitle(), doc.getScope(), draft.text()));
        }
        // 片段数回写 DB，并同步到返回实体（新建文档响应不再停留在占位的 0）
        documentMapper.updateSegmentCount(doc.getId(), drafts.size());
        doc.setSegmentCount(drafts.size());
        // 新片段全部落库成功后才动活跃索引：先整体删旧再整体添新
        if (current != null) {
            current.removeAll(metadataKey("documentId").isEqualTo(String.valueOf(doc.getId())));
            if (!ids.isEmpty()) {
                current.addAll(ids, embeddings, segments);
            }
        }
        return drafts.size();
    }

    /**
     * 按原文本重建某文档索引（依赖 {@code raw_text}，缺失时无法重建）。
     */
    public synchronized int reindex(Long docId) {
        RagDocument doc = requireDocument(docId);
        if (doc.getStatus() == null || doc.getStatus() != 1) {
            throw new BusinessException(400, "文档已停用，请先启用后再重建索引");
        }
        if (doc.getRawText() == null || doc.getRawText().isBlank()) {
            throw new BusinessException(400, "该文档未保存原文，无法重建索引，请删除后重新录入");
        }
        int count = indexDocument(doc);
        log.info("[RAG] 文档索引已重建：id={} 片段 {} 条", docId, count);
        return count;
    }

    /**
     * 启用 / 停用文档，并同步内存索引（DB 片段一律保留，便于重新启用）。
     * <p>
     * 检索与检索测试只读内存索引、不查 {@code status}，所以状态变更必须同时改索引：
     * 仅改 DB 的话，停用要到重启（启动加载带 {@code status = 1}）或全量重建后才生效。
     * 启用时按库内已有向量回填，不重新切分与嵌入（若要换切分参数请重新启用后点重建）。
     */
    public synchronized void setDocumentStatus(Long docId, int status) {
        RagDocument doc = requireDocument(docId);
        int normalized = status == 1 ? 1 : 0;
        documentMapper.updateStatus(docId, normalized);
        InMemoryEmbeddingStore<TextSegment> current = store;
        if (normalized == 0) {
            if (current != null) {
                current.removeAll(metadataKey("documentId").isEqualTo(String.valueOf(docId)));
            }
            log.info("[RAG] 文档已停用并移出内存索引：id={} title={}", docId, doc.getTitle());
            return;
        }
        int added = current == null ? 0
                : addAll(current, segmentMapper.selectByDocumentIdWithEmbedding(docId), model().dimension());
        log.info("[RAG] 文档已启用并加回内存索引：id={} title={} 片段 {} 条", docId, doc.getTitle(), added);
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
     * 全量重建索引：先在内存中完成全部启用文档的切分与嵌入（构建阶段不碰片段表与活跃索引），
     * 构建全部成功后才清空片段表、重写新片段并整体替换内存索引——构建中途失败时旧数据原封不动。
     * 供切换参数、切换 embedding 模型后手动触发。
     */
    public synchronized int rebuildAll() {
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        List<RagDocument> docs = documentMapper.selectEnabled();
        // 构建阶段：只产出切片草稿，任何一篇切分/嵌入失败都直接抛出，旧片段表与活跃索引不受影响
        List<RebuildItem> built = new ArrayList<>(docs.size());
        for (RagDocument doc : docs) {
            built.add(new RebuildItem(doc, splitAndEmbed(doc, cfg.getChunkSize(), cfg.getChunkOverlap())));
        }
        // 提交阶段：构建已全部成功，才允许动旧数据
        segmentMapper.deleteAll();
        InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
        int total = 0;
        for (RebuildItem item : built) {
            int seq = 0;
            for (SegmentDraft draft : item.drafts()) {
                persistSegment(item.doc(), seq++, draft, fresh);
            }
            documentMapper.updateSegmentCount(item.doc().getId(), item.drafts().size());
            total += item.drafts().size();
        }
        store = fresh;
        configService.updateRagEmbeddingInfo(LibraryConfigService.EMBEDDING_MODEL_NAME, model().dimension());
        ready = true;
        log.info("[RAG] 全量重建完成：文档 {} 篇，片段 {} 条", docs.size(), total);
        return total;
    }

    // 检索

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

    // 内部实现

    /** 切片草稿：文本 + 向量，索引构建的中间产物（构建阶段不落库不进索引） */
    private record SegmentDraft(String text, Embedding embedding) {}

    /** 全量重建提交项：文档 + 已构建完成的切片草稿 */
    private record RebuildItem(RagDocument doc, List<SegmentDraft> drafts) {}

    /**
     * 切分 → 逐片向量化，产出切片草稿。不落库、不进索引，失败时对 DB 与索引无任何副作用。
     *
     * @return 切片草稿列表（原文为空时为空列表）
     */
    private List<SegmentDraft> splitAndEmbed(RagDocument doc, int chunkSize, int chunkOverlap) {
        String text = doc.getRawText();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TextSegment> chunks = DocumentSplitters.recursive(chunkSize, chunkOverlap)
                .split(Document.from(text));
        List<SegmentDraft> drafts = new ArrayList<>(chunks.size());
        for (TextSegment chunk : chunks) {
            drafts.add(new SegmentDraft(chunk.text(), model().embed(chunk.text()).content()));
        }
        return drafts;
    }

    /** 切片草稿落库（回填生成主键）并加入内存索引；target 为 null 时只落库不进索引 */
    private RagSegment persistSegment(RagDocument doc, int seq, SegmentDraft draft, InMemoryEmbeddingStore<TextSegment> target) {
        RagSegment seg = new RagSegment();
        seg.setDocumentId(doc.getId());
        seg.setSeq(seq);
        seg.setContent(draft.text());
        seg.setEmbedding(toBytes(draft.embedding().vector()));
        seg.setCreateTime(LocalDateTime.now());
        segmentMapper.insert(seg);
        if (target != null) {
            target.add(String.valueOf(seg.getId()), draft.embedding(),
                    toSegment(doc.getId(), doc.getTitle(), doc.getScope(), draft.text()));
        }
        return seg;
    }

    /**
     * 把库内片段（含 embedding 字节）批量灌入内存索引；维度不符的跳过。
     *
     * @return 实际入索引的片段数
     */
    private int addAll(InMemoryEmbeddingStore<TextSegment> target, List<RagSegment> segments, int dim) {
        int added = 0;
        for (RagSegment s : segments) {
            if (s.getEmbedding() == null || s.getEmbedding().length != dim * Float.BYTES) {
                continue;
            }
            target.add(String.valueOf(s.getId()),
                    Embedding.from(toFloats(s.getEmbedding())),
                    toSegment(s.getDocumentId(), s.getDocTitle(), s.getDocScope(), s.getContent()));
            added++;
        }
        return added;
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

    /** float[] → float32 小端序字节（字节数 = 维度 × 4，维度由 embedding 模型决定，当前 512） */
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