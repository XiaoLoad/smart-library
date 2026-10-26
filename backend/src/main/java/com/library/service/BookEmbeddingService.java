package com.library.service;

import com.library.entity.Book;
import com.library.entity.BookEmbedding;
import com.library.entity.Category;
import com.library.mapper.BookEmbeddingMapper;
import com.library.mapper.BookMapper;
import com.library.mapper.CategoryMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 图书向量服务：馆藏图书元数据向量化与语义召回。
 * <p>
 * 存储结构（与 {@link RagIndexService} 同构）：
 * <pre>
 * book_embeddings（MySQL，唯一数据源：拼接文本 + float32 向量）
 *        ↓ 启动时异步加载
 * InMemoryEmbeddingStore（进程内，检索用；千级书目暴力余弦 &lt; 10ms）
 * </pre>
 * 向量化文本 = 书名 + 作者 + 出版社 + 分类名 + 简介，让自然语言描述
 * （"类似三体的硬科幻"）能通过语义相似度命中关键词匹配不到的图书。
 */
@Service
public class BookEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BookEmbeddingService.class);

    @Autowired
    private BookEmbeddingMapper embeddingMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /** 本地 ONNX 中文 embedding 模型，懒加载（与 RagIndexService 共享实现，各自实例） */
    private volatile EmbeddingModel embeddingModel;

    /** 内存索引；重建时构建新实例后整体替换，检索侧只读该 volatile 引用 */
    private volatile InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    /** 索引是否已加载就绪；未就绪时智能检索只走关键词召回 */
    private volatile boolean ready = false;

    // 启动加载

    /**
     * 启动时异步加载全量图书向量到内存索引。
     * 索引为空但馆藏不为空时（首次部署/新装环境），自动触发一次全量构建。
     */
    @PostConstruct
    public void loadOnStartup() {
        taskExecutor.execute(this::loadFromDatabase);
    }

    private synchronized void loadFromDatabase() {
        try {
            List<BookEmbedding> rows = embeddingMapper.selectAllWithEmbedding();
            int dim = model().dimension();
            InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
            int added = 0;
            for (BookEmbedding e : rows) {
                if (e.getEmbedding() == null || e.getEmbedding().length != dim * Float.BYTES) {
                    continue;
                }
                fresh.add(String.valueOf(e.getBookId()), Embedding.from(toFloats(e.getEmbedding())),
                        toSegment(e));
                added++;
            }
            store = fresh;
            log.info("[SmartSearch] 图书向量索引加载完成：{} 条", added);

            // 首次部署：向量库为空且馆藏有书 → 自动全量构建
            if (added == 0 && bookMapper.selectList(null, null).size() > 0) {
                log.info("[SmartSearch] 检测到向量库为空，自动构建馆藏图书索引");
                rebuildAll();
            }
            ready = true;
        } catch (Exception e) {
            log.error("[SmartSearch] 图书向量索引加载失败，本次运行语义召回不可用", e);
        }
    }

    // 构建 / 重建

    /**
     * 全量重建：按馆藏当前数据重新向量化全部图书。
     * 供首次构建、embedding 模型变更后手动/自动触发。
     *
     * @return 构建的图书条数
     */
    public synchronized int rebuildAll() {
        List<Book> books = bookMapper.selectList(null, null);
        Map<Long, String> categoryNames = loadCategoryNames();
        embeddingMapper.deleteAll();
        InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
        int total = 0;
        for (Book book : books) {
            try {
                indexOne(fresh, book, categoryNames);
                total++;
            } catch (Exception e) {
                log.warn("[SmartSearch] 图书向量化失败，跳过：id={} title={} err={}",
                        book.getId(), book.getTitle(), e.getMessage());
            }
        }
        store = fresh;
        ready = true;
        log.info("[SmartSearch] 图书向量索引全量重建完成：{} 条", total);
        return total;
    }

    /**
     * 单本图书入库/更新向量（新书新增、图书信息变更后调用，增量维护）。
     */
    public synchronized void indexBook(Long bookId) {
        Book book = bookMapper.selectById(bookId);
        if (book == null || book.getStatus() == null || book.getStatus() != 1) {
            embeddingMapper.deleteByBookId(bookId);
            removeFromStore(bookId);
            return;
        }
        indexOne(store, book, loadCategoryNames());
    }

    /**
     * 图书下架/删除时移出索引与向量库。
     */
    public synchronized void removeBook(Long bookId) {
        embeddingMapper.deleteByBookId(bookId);
        removeFromStore(bookId);
    }

    private void indexOne(InMemoryEmbeddingStore<TextSegment> target, Book book, Map<Long, String> categoryNames) {
        String text = buildText(book, categoryNames);
        Embedding embedding = model().embed(text).content();
        BookEmbedding row = new BookEmbedding();
        row.setBookId(book.getId());
        row.setContent(text);
        row.setEmbedding(toBytes(embedding.vector()));
        row.setTitle(book.getTitle());
        if (embeddingMapper.selectByBookId(book.getId()) == null) {
            embeddingMapper.insert(row);
        } else {
            embeddingMapper.updateByBookId(row);
        }
        if (target != null) {
            removeFromStore(book.getId());
            target.add(String.valueOf(book.getId()), embedding, toSegment(row));
        }
    }

    /** 拼接参与向量化的文本：书名 + 作者 + 出版社 + 分类名 + 简介 */
    private String buildText(Book book, Map<Long, String> categoryNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(book.getTitle());
        if (book.getAuthor() != null && !book.getAuthor().isBlank()) {
            sb.append('，').append(book.getAuthor());
        }
        if (book.getPublisher() != null && !book.getPublisher().isBlank()) {
            sb.append('，').append(book.getPublisher());
        }
        String catName = categoryNames.get(book.getCategoryId());
        if (catName != null) {
            sb.append('，').append(catName);
        }
        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            // 截断过长简介，控制向量输入长度（简介只影响语义，前 300 字足够）
            String desc = book.getDescription().replaceAll("\\s+", " ").trim();
            if (desc.length() > 300) {
                desc = desc.substring(0, 300);
            }
            sb.append('。').append(desc);
        }
        return sb.toString();
    }

    private Map<Long, String> loadCategoryNames() {
        Map<Long, String> names = new HashMap<>();
        for (Category c : categoryMapper.selectAll()) {
            names.put(c.getId(), c.getCategoryName());
        }
        return names;
    }

    private void removeFromStore(Long bookId) {
        // remove(String) 直接按 id 删除；RagIndexService 用 metadata filter，这里单条 id 删除更直接
        try {
            store.remove(String.valueOf(bookId));
        } catch (Exception ignored) {
            // 不存在时忽略
        }
    }

    // 语义召回

    /**
     * 语义召回 Top N 图书。
     *
     * @return 匹配结果（含图书展示字段与相似度分数），索引未就绪时返回空列表
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        if (!ready || query == null || query.isBlank()) {
            return List.of();
        }
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(model().embed(query).content())
                .maxResults(maxResults)
                .minScore(0.35)
                .build();
        return store.search(request).matches();
    }

    /** 索引是否就绪（未就绪时智能检索降级为纯关键词检索） */
    public boolean isReady() {
        return ready;
    }

    /** 内存索引图书条数 */
    public int indexedCount() {
        return store.size();
    }

    // 内部实现

    private TextSegment toSegment(BookEmbedding e) {
        Metadata metadata = Metadata.from(Map.of(
                "bookId", String.valueOf(e.getBookId()),
                "title", e.getTitle() == null ? "" : e.getTitle()));
        return TextSegment.from(e.getContent(), metadata);
    }

    /** 懒加载本地 ONNX 模型（避免拖慢启动） */
    private EmbeddingModel model() {
        EmbeddingModel m = embeddingModel;
        if (m == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
                }
                m = embeddingModel;
            }
        }
        return m;
    }

    /** float[] → float32 小端序字节（512 维 = 2048 字节） */
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
