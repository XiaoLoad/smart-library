package com.library.service;

import com.library.entity.Book;
import com.library.entity.Borrow;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowMapper;
import com.library.mapper.UserMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图书推荐服务：三层混合推荐（与 StatsService「真实借阅」口径一致）。
 *
 * <pre>
 * ① 个性化协同过滤（ItemCF）：基于 borrows 借阅共现——读过 A 的人还读过什么。
 *    共现矩阵按全量借阅记录 SQL 拉取后内存聚合，结果缓存 10 分钟（馆级规模查询 <50ms）。
 * ② 内容语义相似：复用 {@link BookEmbeddingService} 本地 bge 向量（书名+作者+分类+简介），
 *    以图书自身文本为 query 召回 Top-K，冷启动可用、零 API 成本。
 * ③ 热门/同院系热门兜底：无足够历史行为的用户走画像（department/major）加权热门榜。
 * </pre>
 * 融合策略按用户行为量自动降级：真实借阅 ≥ {@link #CF_MIN_HISTORY} 条走 ①+② 加权融合，
 * 否则走 ③ 画像热门；每个推荐项附带推荐理由（reason），供前端标签与 AI 转述。
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    /** 启用协同过滤所需的用户最少真实借阅条数（低于此值走画像热门兜底） */
    private static final int CF_MIN_HISTORY = 5;
    /** ItemCF 共现缓存有效期（毫秒） */
    private static final long CF_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 候选池扩充倍数（融合打分前多召回一些，再截断到请求数量） */
    private static final int CANDIDATE_POOL = 40;

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private BorrowMapper borrowMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BookEmbeddingService bookEmbeddingService;

    /** ItemCF 共现矩阵缓存：bookId → (相似书 bookId → 共现读者数)。volatile 整体替换，读侧无锁 */
    private volatile Map<Long, Map<Long, Integer>> coMatrix = Map.of();
    private volatile long coMatrixLoadedAt = 0;

    // ============ 对外入口 ============

    /**
     * 个性化推荐（仪表盘「为你推荐」）。
     * 行为足够走 ①+② 融合，否则降级 ③ 画像热门；全部失败再降全馆热门。
     */
    public List<Map<String, Object>> recommendForUser(Long userId, int limit) {
        List<Borrow> myBorrows = borrowMapper.selectList(userId, false, null, null)
                .stream().filter(b -> b.getBorrowTime() != null).toList();
        Set<Long> readBookIds = myBorrows.stream().map(Borrow::getBookId).collect(java.util.stream.Collectors.toSet());

        List<Map<String, Object>> result;
        if (readBookIds.size() >= CF_MIN_HISTORY) {
            result = fuseCfAndContent(userId, readBookIds, limit);
        } else {
            result = profileHotFallback(userId, readBookIds, limit);
        }
        if (result.isEmpty()) {
            // 终极兜底：全馆热门（新馆/全馆无借阅数据时也有内容可展示）
            result = hotFallback(readBookIds, limit);
        }
        return result;
    }

    /**
     * 相似图书（详情页「读过此书的人还读」）：借阅共现优先，向量语义补位。
     */
    public List<Map<String, Object>> similarBooks(Long bookId, int limit) {
        Book source = bookMapper.selectById(bookId);
        if (source == null) {
            return List.of();
        }
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();

        // ① 借阅共现相似（读过 A 的人还读过 B，按共现读者数排序）
        Map<Long, Integer> co = coOccurrence().getOrDefault(bookId, Map.of());
        for (Map.Entry<Long, Integer> e : co.entrySet()) {
            if (e.getKey().equals(bookId)) {
                continue;
            }
            Map<String, Object> m = baseItem(e.getKey());
            if (m == null) {
                continue;
            }
            m.put("reason", "读过《" + source.getTitle() + "》的人也在读");
            m.put("score", 1000 + e.getValue()); // 共现权重高于语义
            merged.put(e.getKey(), m);
        }

        // ② 向量语义相似补位
        for (EmbeddingMatch<TextSegment> match : bookEmbeddingService.search(buildBookQuery(source), CANDIDATE_POOL)) {
            Long id = matchBookId(match);
            if (id == null) {
                continue;
            }
            if (id.equals(bookId) || merged.containsKey(id)) {
                continue;
            }
            Map<String, Object> m = baseItem(id);
            if (m == null) {
                continue;
            }
            m.put("reason", "内容与《" + source.getTitle() + "》相似");
            m.put("score", (int) Math.round(match.score() * 500));
            merged.put(id, m);
        }

        return merged.entrySet().stream()
                .sorted((a, b) -> Integer.compare((int) b.getValue().get("score"), (int) a.getValue().get("score")))
                .limit(limit)
                .map(e -> stripScore(e.getValue()))
                .toList();
    }

    // ============ ①+② 融合 ============

    private List<Map<String, Object>> fuseCfAndContent(Long userId, Set<Long> readBookIds, int limit) {
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();

        // ① ItemCF：对我读过的每本书，取共现 Top 候选，共现数累加
        Map<Long, Integer> cfScores = new HashMap<>();
        Map<Long, Long> cfFrom = new HashMap<>();
        Map<Long, Map<Long, Integer>> matrix = coOccurrence();
        for (Long bookId : readBookIds) {
            for (Map.Entry<Long, Integer> e : matrix.getOrDefault(bookId, Map.of()).entrySet()) {
                if (readBookIds.contains(e.getKey())) {
                    continue;
                }
                cfScores.merge(e.getKey(), e.getValue(), Integer::sum);
                cfFrom.putIfAbsent(e.getKey(), bookId);
            }
        }
        cfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(CANDIDATE_POOL)
                .forEach(e -> {
                    Map<String, Object> m = baseItem(e.getKey());
                    if (m == null) {
                        return;
                    }
                    Book from = bookMapper.selectById(cfFrom.get(e.getKey()));
                    m.put("reason", "与你读过的《" + (from != null ? from.getTitle() : "图书") + "》相似的人气书");
                    m.put("score", 1000 + e.getValue());
                    merged.put(e.getKey(), m);
                });

        // ② 向量语义：用我最近读过的书做语义扩散
        Map<Long, Double> vecScores = new HashMap<>();
        for (Long bookId : readBookIds) {
            Book source = bookMapper.selectById(bookId);
            if (source == null) {
                continue;
            }
            for (EmbeddingMatch<TextSegment> match : bookEmbeddingService.search(buildBookQuery(source), 10)) {
                Long id = matchBookId(match);
                if (id == null || readBookIds.contains(id)) {
                    continue;
                }
                vecScores.merge(id, match.score(), Double::sum);
            }
        }
        vecScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(CANDIDATE_POOL)
                .forEach(e -> {
                    Map<String, Object> exist = merged.get(e.getKey());
                    if (exist != null) {
                        exist.put("score", (int) exist.get("score") + (int) Math.round(e.getValue() * 500));
                        return;
                    }
                    Map<String, Object> m = baseItem(e.getKey());
                    if (m == null) {
                        return;
                    }
                    m.put("reason", "符合你的阅读口味");
                    m.put("score", (int) Math.round(e.getValue() * 500));
                    merged.put(e.getKey(), m);
                });

        // 借出无货的书降权排序（不剔除——预约/等回库也有价值，但排在可借书后面）
        return merged.entrySet().stream()
                .sorted((a, b) -> {
                    int sa = (int) a.getValue().get("score") + availBonus(a.getValue());
                    int sb = (int) b.getValue().get("score") + availBonus(b.getValue());
                    return Integer.compare(sb, sa);
                })
                .limit(limit)
                .map(e -> stripScore(e.getValue()))
                .toList();
    }

    /** 可借册数 > 0 加权，让可借的书排前面 */
    private int availBonus(Map<String, Object> item) {
        Object avail = item.get("availableCount");
        return (avail instanceof Integer n && n > 0) ? 30 : 0;
    }

    // ============ ③ 画像热门兜底 ============

    private List<Map<String, Object>> profileHotFallback(Long userId, Set<Long> readBookIds, int limit) {
        User me = userMapper.selectById(userId);
        String keyword = me == null ? null : (me.getMajor() != null && !me.getMajor().isBlank()
                ? me.getMajor() : me.getDepartment());
        List<Book> candidates;
        if (keyword != null) {
            // 画像关键词（专业/院系）检索在架图书，命中即标"与你专业相关"
            candidates = bookMapper.selectList(keyword, null).stream()
                    .filter(b -> b.getStatus() != null && b.getStatus() == 1)
                    .filter(b -> !readBookIds.contains(b.getId()))
                    .limit(CANDIDATE_POOL)
                    .toList();
        } else {
            candidates = List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Book b : candidates) {
            Map<String, Object> m = baseItem(b.getId());
            if (m == null) {
                continue;
            }
            m.put("reason", "与你的" + (me != null && me.getMajor() != null && !me.getMajor().isBlank() ? "专业" : "院系") + "相关");
            m.put("score", 100);
            result.add(m);
        }
        if (result.size() < limit) {
            result.addAll(hotFallback(readBookIds, limit - result.size()));
        }
        return result.stream().limit(limit).map(this::stripScore).toList();
    }

    /** 全馆热门兜底（复用 StatsService 真实借阅口径） */
    private List<Map<String, Object>> hotFallback(Set<Long> readBookIds, int limit) {
        List<Map<String, Object>> hot = borrowHotTop();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> h : hot) {
            Long bookId = (Long) h.get("bookId");
            if (readBookIds.contains(bookId)) {
                continue;
            }
            Map<String, Object> m = baseItem(bookId);
            if (m == null) {
                continue;
            }
            Object rank = h.get("rank");
            m.put("reason", "全馆借阅榜第 " + rank + " 名");
            result.add(m);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /** 真实借阅 Top10（与仪表盘热门榜同口径，缓存 10 分钟） */
    private List<Map<String, Object>> borrowHotTop() {
        Map<String, Object> cached = hotCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - hotCacheAt < CF_CACHE_TTL_MS) {
            return (List<Map<String, Object>>) cached.get("hot");
        }
        synchronized (this) {
            if (hotCache != null && System.currentTimeMillis() - hotCacheAt < CF_CACHE_TTL_MS) {
                return (List<Map<String, Object>>) hotCache.get("hot");
            }
            List<Borrow> all = borrowMapper.selectList(null, true, null, null).stream()
                    .filter(b -> b.getBorrowTime() != null).toList();
            Map<Long, Long> count = new HashMap<>();
            for (Borrow b : all) {
                count.merge(b.getBookId(), 1L, Long::sum);
            }
            List<Map.Entry<Long, Long>> sorted = count.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .limit(10)
                    .toList();
            List<Map<String, Object>> hot = new ArrayList<>();
            int rank = 1;
            for (Map.Entry<Long, Long> e : sorted) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", rank++);
                m.put("bookId", e.getKey());
                m.put("count", e.getValue());
                hot.add(m);
            }
            Map<String, Object> box = new HashMap<>();
            box.put("hot", hot);
            hotCache = box;
            hotCacheAt = System.currentTimeMillis();
            return hot;
        }
    }

    private volatile Map<String, Object> hotCache = null;
    private volatile long hotCacheAt = 0;

    // ============ ItemCF 共现 ============

    /**
     * 借阅共现矩阵：bookId → (其他书 bookId → 共同借阅人数)。
     * 全量借阅拉取后内存聚合（馆级规模万级记录 <100ms），缓存 {@link #CF_CACHE_TTL_MS}。
     */
    private Map<Long, Map<Long, Integer>> coOccurrence() {
        long now = System.currentTimeMillis();
        if (now - coMatrixLoadedAt < CF_CACHE_TTL_MS && !coMatrix.isEmpty()) {
            return coMatrix;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - coMatrixLoadedAt < CF_CACHE_TTL_MS && !coMatrix.isEmpty()) {
                return coMatrix;
            }
            try {
                List<Borrow> all = borrowMapper.selectList(null, true, null, null).stream()
                        .filter(b -> b.getBorrowTime() != null && b.getUserId() != null).toList();
                // user → 该用户真实借过的所有书
                Map<Long, Set<Long>> userBooks = new HashMap<>();
                for (Borrow b : all) {
                    userBooks.computeIfAbsent(b.getUserId(), k -> new HashSet<>()).add(b.getBookId());
                }
                // 两两共现计数
                Map<Long, Map<Long, Integer>> matrix = new HashMap<>();
                for (Set<Long> books : userBooks.values()) {
                    List<Long> list = new ArrayList<>(books);
                    for (int i = 0; i < list.size(); i++) {
                        for (int j = i + 1; j < list.size(); j++) {
                            matrix.computeIfAbsent(list.get(i), k -> new HashMap<>())
                                    .merge(list.get(j), 1, Integer::sum);
                            matrix.computeIfAbsent(list.get(j), k -> new HashMap<>())
                                    .merge(list.get(i), 1, Integer::sum);
                        }
                    }
                }
                coMatrix = matrix;
                coMatrixLoadedAt = System.currentTimeMillis();
                log.info("[Recommend] 借阅共现矩阵构建完成：{} 本图书参与，耗时缓存 10 分钟", matrix.size());
                return matrix;
            } catch (Exception e) {
                log.error("[Recommend] 共现矩阵构建失败，本次降级向量/热门推荐", e);
                coMatrixLoadedAt = System.currentTimeMillis(); // 失败也置时间戳，避免频繁重试打库
                return coMatrix;
            }
        }
    }

    // ============ 工具方法 ============

    /** 从向量匹配结果提取图书 ID（与 BookEmbeddingService.toSegment 的 metadata 键一致） */
    private Long matchBookId(EmbeddingMatch<TextSegment> match) {
        try {
            return Long.parseLong(match.embedded().metadata().getString("bookId"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 构造推荐项基础字段（图书展示信息），图书不存在或已下架返回 null */
    private Map<String, Object> baseItem(Long bookId) {
        Book b = bookMapper.selectById(bookId);
        if (b == null || b.getStatus() == null || b.getStatus() != 1) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookId", b.getId());
        m.put("title", b.getTitle());
        m.put("author", b.getAuthor());
        m.put("coverUrl", b.getCoverUrl());
        m.put("isbn", b.getIsbn());
        m.put("location", b.getLocation());
        m.put("availableCount", b.getAvailableCount());
        m.put("totalCount", b.getTotalCount());
        return m;
    }

    /** 向量化查询文本：与 BookEmbeddingService.buildText 同构（书名+作者+分类+简介前缀） */
    private String buildBookQuery(Book b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.getTitle());
        if (b.getAuthor() != null && !b.getAuthor().isBlank()) {
            sb.append('，').append(b.getAuthor());
        }
        if (b.getDescription() != null && !b.getDescription().isBlank()) {
            String desc = b.getDescription().replaceAll("\\s+", " ").trim();
            sb.append('。').append(desc, 0, Math.min(150, desc.length()));
        }
        return sb.toString();
    }

    private Map<String, Object> stripScore(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.remove("score");
        return copy;
    }

    /** 供 AiToolContext 注入推荐服务用（AI 工具调用入口） */
    public Map<String, Object> recommendForUserTool(Long userId, int limit) {
        List<Map<String, Object>> items = recommendForUser(userId, limit);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", items.size());
        r.put("items", items);
        r.put("nextStep", "把推荐书目的书名与推荐理由转述给用户；用户想借其中某本时，"
                + "引导其到馆藏页办理或直接调用 applyBorrow（需 title 与 isbn）");
        return r;
    }
}
