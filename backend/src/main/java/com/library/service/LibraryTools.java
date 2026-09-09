package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.*;
import com.library.mapper.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;
import java.util.function.Function;

/**
 * 暴露给 AI 大模型调用的工具集合。
 * <p>
 * 每次会话由 {@link AiService} 创建一个新实例，将当前用户身份（userId/userType）
 * 烘焙进实例字段，避免在 langchain4j 异步线程中丢失 {@link com.library.security.CurrentUser}。
 * 所有工具方法统一返回 {@code Map<String,Object>} 以保证返回类型稳定。
 * <p>
 * 工具调用过程通过 {@link ToolEventEmitter} 回调向前端 SSE 推送 tool 事件，
 * 用于在 UI 上展示「正在调用工具 / 工具返回」状态。
 * <p>
 * P2.10 结构化输出：end 阶段携带工具返回的精简数据快照（JSON 字符串），
 * 供前端工具卡片展开查看，避免把原始数据直接混入 detail 文本。
 */
public class LibraryTools {

    /** 工具调用事件发射器（可选，未设置时不发送事件） */
    public interface ToolEventEmitter {
        /**
         * 发送工具调用事件。
         *
         * @param toolName 工具名称（如 queryBooks）
         * @param phase    阶段：start / end / error
         * @param detail   简短描述（如返回条数、错误信息）
         * @param data     结构化数据快照（JSON 字符串，仅 end 阶段非空；start/error 阶段为 null）
         */
        void emit(String toolName, String phase, String detail, String data);
    }

    /** 数据快照最大字符数（避免 SSE 包过大） */
    private static final int DATA_SNAPSHOT_MAX_LEN = 600;
    /** 列表类快照保留的最大条目数（避免长列表爆包） */
    private static final int LIST_SNAPSHOT_LIMIT = 3;

    private final Long userId;
    private final Integer userType;
    private final BorrowMapper borrowMapper;
    private final BookMapper bookMapper;
    private final FineMapper fineMapper;
    private final AnnouncementMapper announcementMapper;
    private final BorrowLimitMapper borrowLimitMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    /** 当前请求的工具事件发射器，由 AiService 在创建工具实例时注入 */
    private ToolEventEmitter eventEmitter;

    public LibraryTools(Long userId, Integer userType,
                        BorrowMapper borrowMapper, BookMapper bookMapper,
                        FineMapper fineMapper, AnnouncementMapper announcementMapper,
                        BorrowLimitMapper borrowLimitMapper, UserMapper userMapper,
                        ObjectMapper objectMapper) {
        this.userId = userId;
        this.userType = userType;
        this.borrowMapper = borrowMapper;
        this.bookMapper = bookMapper;
        this.fineMapper = fineMapper;
        this.announcementMapper = announcementMapper;
        this.borrowLimitMapper = borrowLimitMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    /** 注入本次请求的事件发射器 */
    public void setEventEmitter(ToolEventEmitter emitter) {
        this.eventEmitter = emitter;
    }

    /**
     * 包裹工具方法体，统一发射 start / end / error 事件。
     * end 阶段携带工具返回的精简数据快照（结构化 JSON）。
     */
    private <R> R withEvent(String name, String startDetail, Function<Void, R> action) {
        if (eventEmitter != null) {
            eventEmitter.emit(name, "start", startDetail, null);
        }
        try {
            R r = action.apply(null);
            if (eventEmitter != null) {
                eventEmitter.emit(name, "end", describeResult(r), snapshotData(r));
            }
            return r;
        } catch (RuntimeException e) {
            if (eventEmitter != null) {
                eventEmitter.emit(name, "error", e.getMessage(), null);
            }
            throw e;
        }
    }

    /** 根据工具返回值生成简短的完成描述（卡片摘要展示用） */
    private String describeResult(Object r) {
        if (r instanceof Map<?, ?> m) {
            Object total = m.get("total");
            if (total != null) {
                return "返回 " + total + " 条记录";
            }
            Object maxBooks = m.get("maxBooks");
            if (maxBooks != null) {
                Object used = m.get("used");
                Object remaining = m.get("remaining");
                return "最大 " + maxBooks + "，已用 " + used + "，剩余 " + remaining;
            }
            return "完成";
        }
        return "完成";
    }

    /**
     * 生成工具返回值的精简 JSON 快照（P2.10 结构化输出）。
     * <p>
     * 策略：
     * <ul>
     *   <li>列表类结果（含 items）只保留前 {@link #LIST_SNAPSHOT_LIMIT} 条，避免长列表爆包</li>
     *   <li>整体序列化后截断到 {@link #DATA_SNAPSHOT_MAX_LEN} 字符</li>
     *   <li>序列化失败时返回 null（不影响主流程）</li>
     * </ul>
     */
    private String snapshotData(Object r) {
        if (r == null) return null;
        try {
            Object toSerialize = r;
            // 列表类结果：保留 total + 前 N 条 items，便于前端预览
            if (r instanceof Map<?, ?> m && m.get("items") instanceof List<?> list) {
                Map<String, Object> slim = new LinkedHashMap<>();
                slim.put("total", m.get("total"));
                List<Object> head = new ArrayList<>();
                int limit = Math.min(list.size(), LIST_SNAPSHOT_LIMIT);
                for (int i = 0; i < limit; i++) {
                    head.add(list.get(i));
                }
                slim.put("items", head);
                slim.put("truncated", list.size() > limit);
                toSerialize = slim;
            }
            String json = objectMapper.writeValueAsString(toSerialize);
            if (json.length() > DATA_SNAPSHOT_MAX_LEN) {
                return json.substring(0, DATA_SNAPSHOT_MAX_LEN) + "…";
            }
            return json;
        } catch (Exception e) {
            // 序列化失败不影响主流程
            return null;
        }
    }

    /**
     * 查询当前用户本人的借阅记录，可按状态过滤。
     */
    @Tool("查询当前用户本人的借阅记录，可选按状态过滤（0=待取书,1=借阅中,2=已归还,3=已逾期,4=已取消,5=待确认归还）；不传或传 all 表示全部")
    public Map<String, Object> queryMyBorrows(@P("借阅状态：0/1/2/3/4/5 或 all，可省略") String status) {
        return withEvent("queryMyBorrows", "状态：" + (status == null || status.isBlank() ? "全部" : status), v -> {
            Integer statusInt = parseStatus(status);
            List<Borrow> list = borrowMapper.selectList(userId, false, statusInt, null);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Borrow b : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("borrowNo", b.getBorrowNo());
                m.put("bookTitle", b.getBookTitle());
                m.put("status", statusLabel(b.getStatus()));
                m.put("applyTime", str(b.getApplyTime()));
                m.put("borrowTime", str(b.getBorrowTime()));
                m.put("dueTime", str(b.getDueTime()));
                m.put("returnTime", str(b.getReturnTime()));
                m.put("renewCount", b.getRenewCount());
                m.put("renewMaxCount", b.getRenewMaxCount());
                m.put("remark", b.getRemark());
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    /**
     * 按关键词检索馆藏图书（最多返回 10 条）。
     */
    @Tool("按关键词检索馆藏图书（匹配书名/作者/ISBN），最多返回 10 条")
    public Map<String, Object> queryBooks(@P("检索关键词：书名/作者/ISBN 片段") String keyword) {
        return withEvent("queryBooks", "关键词：" + (keyword == null ? "" : keyword), v -> {
            List<Book> list = bookMapper.selectList(keyword, null);
            int limit = Math.min(list.size(), 10);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                Book b = list.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.getId());
                m.put("title", b.getTitle());
                m.put("author", b.getAuthor());
                m.put("publisher", b.getPublisher());
                m.put("isbn", b.getIsbn());
                m.put("location", b.getLocation());
                m.put("availableCount", b.getAvailableCount());
                m.put("totalCount", b.getTotalCount());
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    /**
     * 查询当前用户本人的罚款记录。
     */
    @Tool("查询当前用户本人的罚款记录（0=未缴,1=已缴）")
    public Map<String, Object> queryMyFines() {
        return withEvent("queryMyFines", null, v -> {
            List<Fine> list = fineMapper.selectList(userId, false);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Fine f : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("borrowNo", f.getBorrowNo());
                m.put("bookTitle", f.getBookTitle());
                m.put("amount", f.getAmount() == null ? null : f.getAmount().toPlainString());
                m.put("daysOverdue", f.getDaysOverdue());
                m.put("status", f.getStatus() != null && f.getStatus() == 1 ? "已缴" : "未缴");
                m.put("reason", f.getReason());
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    /**
     * 查询当前用户本人的借阅额度。
     */
    @Tool("查询当前用户本人的借阅额度（最大可借、已用、剩余、逾期数）")
    public Map<String, Object> queryBorrowQuota() {
        return withEvent("queryBorrowQuota", null, v -> {
            User user = userMapper.selectById(userId);
            BorrowLimit limit = user == null ? null : borrowLimitMapper.selectByType(user.getUserType());
            Map<String, Object> m = new LinkedHashMap<>();
            if (limit == null) {
                m.put("maxBooks", 0);
                m.put("used", 0);
                m.put("remaining", 0);
                m.put("overdueCount", 0);
                m.put("canBorrow", false);
            } else {
                int used = borrowMapper.countByUserAndStatuses(userId, List.of(0, 1, 5));
                int overdue = borrowMapper.countOverduePending(userId);
                m.put("maxBooks", limit.getMaxBooks());
                m.put("used", used);
                m.put("remaining", Math.max(0, limit.getMaxBooks() - used));
                m.put("overdueCount", overdue);
                m.put("canBorrow", used < limit.getMaxBooks() && overdue == 0);
            }
            return m;
        });
    }

    /**
     * 查询馆内有效公告（最多 5 条）。
     */
    @Tool("查询馆内最新公告，最多返回 5 条")
    public Map<String, Object> queryAnnouncements() {
        return withEvent("queryAnnouncements", null, v -> {
            List<Announcement> list = announcementMapper.selectList();
            int limit = Math.min(list.size(), 5);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                Announcement a = list.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("title", a.getTitle());
                m.put("content", truncate(a.getContent(), 120));
                m.put("top", a.getTop());
                m.put("publishTime", str(a.getPublishTime()));
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    // ===== 工具方法 =====

    private Map<String, Object> result(int total, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("items", items);
        return m;
    }

    private Integer parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) return null;
        try {
            return Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String statusLabel(Integer s) {
        if (s == null) return "未知";
        return switch (s) {
            case 0 -> "待取书";
            case 1 -> "借阅中";
            case 2 -> "已归还";
            case 3 -> "已逾期";
            case 4 -> "已取消";
            case 5 -> "待确认归还";
            default -> "状态" + s;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
