package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.entity.Borrow;
import com.library.entity.BorrowLimit;
import com.library.entity.Fine;
import com.library.entity.Notification;
import com.library.entity.User;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读者工具（仅非管理员注册）：本人借阅/罚款/额度/通知查询，
 * 以及借阅申请、续借、归还申请三个自助写操作。
 * <p>
 * 写操作的调用前确认由系统提示词的核心规则约束（必须先复述书名/借阅码并获用户明确同意）。
 */
public class ReaderTools extends BaseLibraryTools {

    /** 书名匹配到多本时，返回给模型用于消歧的候选上限 */
    private static final int BORROW_CANDIDATE_LIMIT = 5;

    public ReaderTools(Long userId, Integer userType, AiToolContext ctx) {
        super(userId, userType, ctx);
    }

    /**
     * 查询当前用户本人的借阅记录，可按状态过滤、按关键词搜索。
     */
    @Tool("查询当前用户本人的借阅记录，可按状态过滤（0=待取书,1=借阅中,2=已归还,3=已逾期,4=已取消,5=待确认归还），"
            + "可按书名或借阅码关键词搜索；状态不传或传 all 表示全部")
    public Map<String, Object> queryMyBorrows(@P("借阅状态：0/1/2/3/4/5 或 all，可省略") String status,
                                              @P("搜索关键词：书名或借阅码片段，可省略") String search) {
        return withEvent("queryMyBorrows",
                "状态：" + (status == null || status.isBlank() ? "全部" : status)
                        + (search != null && !search.isBlank() ? "，关键词：" + search : ""),
                v -> {
                    List<Borrow> list = ctx.borrowMapper.selectList(userId, false,
                            parseStatus(status), blankToNull(search));
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
                        m.put("pickupDeadline", str(b.getPickupDeadline()));
                        m.put("remark", b.getRemark());
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
            List<Fine> list = ctx.fineMapper.selectList(userId, false);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Fine f : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("borrowNo", f.getBorrowNo());
                m.put("bookTitle", f.getBookTitle());
                m.put("amount", f.getAmount() == null ? null : f.getAmount().toPlainString());
                m.put("daysOverdue", f.getDaysOverdue());
                m.put("status", fineStatusLabel(f.getStatus()));
                m.put("reason", f.getReason());
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    /**
     * 查询当前用户本人的借阅额度。
     */
    @Tool("查询当前用户本人的借阅额度（最大可借、已用、剩余、逾期数、当前是否可借）")
    public Map<String, Object> queryBorrowQuota() {
        return withEvent("queryBorrowQuota", null, v -> {
            User user = ctx.userMapper.selectById(userId);
            BorrowLimit limit = user == null ? null : ctx.borrowLimitMapper.selectByType(user.getUserType());
            Map<String, Object> m = new LinkedHashMap<>();
            if (limit == null) {
                m.put("maxBooks", 0);
                m.put("used", 0);
                m.put("remaining", 0);
                m.put("overdueCount", 0);
                m.put("canBorrow", false);
            } else {
                int used = ctx.borrowMapper.countByUserAndStatuses(userId, List.of(0, 1, 5));
                int overdue = ctx.borrowMapper.countOverduePending(userId);
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
     * 查询当前用户本人的通知消息（最多 10 条）。
     */
    @Tool("查询当前用户本人的通知消息（如取书提醒、逾期催还、罚款通知等），最多返回 10 条，按时间倒序")
    public Map<String, Object> queryMyNotifications() {
        return withEvent("queryMyNotifications", null, v -> {
            List<Notification> list = ctx.notificationMapper.selectByUserId(userId);
            int limit = Math.min(list.size(), 10);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                Notification n = list.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", n.getId());
                m.put("title", n.getTitle());
                m.put("content", truncate(n.getContent(), 100));
                m.put("isRead", n.getIsRead() != null && n.getIsRead() == 1);
                m.put("createTime", str(n.getCreateTime()));
                items.add(m);
            }
            return result(list.size(), items);
        });
    }

    /**
     * 提交借阅申请（写操作）。
     * <p>
     * 入参刻意不接收图书 ID：ID 需要模型自行在上下文中挑选，曾发生「确认《围城》却按错 ID
     * 借出《理想国》」的事故——服务端只拿到一个数字，无从判断它对应的是否就是用户确认的那本。
     * 改以「用户确认的书名」为锚：ISBN 唯一，能拿到就按 ISBN 精确定位；否则按书名匹配，
     * 命中多本时返回候选交由用户选定，绝不自行选取。
     */
    @Tool("【借阅申请·写操作】当用户表达借阅/借书意图时，应调用本工具提交借阅申请。"
            + "系统会在执行前弹出授权卡，由用户在卡片上确认后才真正生效——你无需先向用户索要确认，直接调用即可。"
            + "bookTitle 必须是用户要借的书名（来自 queryBooks 返回的 title）；"
            + "若 queryBooks 结果含 isbn，务必一并传入——ISBN 唯一，能精确定位书目，可避免同名多版本借错。"
            + "只传书名且匹配到多本时不会办理，而是返回 candidates 候选清单，"
            + "此时必须把候选书目念给用户、让用户选定，再以选定的书名重新调用，禁止自行挑选。"
            + "成功后返回借阅码和取书截止时间，需提醒用户在截止时间前到服务台取书")
    public Map<String, Object> applyBorrow(@P("用户确认的书名，来自 queryBooks 返回的 title，禁止猜测") String bookTitle,
                                           @P(value = "图书 ISBN，来自 queryBooks 返回的 isbn；唯一标识，优先于书名，可省略",
                                                   required = false) String isbn) {
        return withWriteEvent("applyBorrow", "借阅《" + bookTitle + "》",
                borrowRows(bookTitle, isbn),
                Map.of("bookTitle", bookTitle == null ? "" : bookTitle,
                        "isbn", isbn == null ? "" : isbn),
                v -> doApplyBorrow(bookTitle, isbn));
    }

    /**
     * 执行已确认的借阅申请（授权卡确认后由 {@link AiService} 调用，绕过门禁）。
     * <p>
     * 与 {@link #applyBorrow} 的区别仅在于：前者是「模型发起」（过门禁），
     * 本方法是「用户已确认」（直接执行）。实际业务逻辑完全一致，抽取为公共实现避免两处漂移。
     */
    public Map<String, Object> applyBorrowConfirmed(String bookTitle, String isbn) {
        return doApplyBorrow(bookTitle, isbn);
    }

    /** 借阅的实际业务逻辑（门禁与确认后执行共用） */
    private Map<String, Object> doApplyBorrow(String bookTitle, String isbn) {
        BookMatch match = locateBook(bookTitle, isbn);
        if (match.book() == null) {
            return ambiguousBorrow(bookTitle, match.candidates());
        }
        Borrow b = ctx.borrowService.apply(userId, match.book().getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("borrowNo", b.getBorrowNo());
        m.put("bookId", b.getBookId());
        m.put("bookTitle", b.getBookTitle());
        m.put("status", statusLabel(b.getStatus()));
        m.put("pickupDeadline", str(b.getPickupDeadline()));
        m.put("message", "已借出《" + b.getBookTitle() + "》，请在取书截止时间前到服务台取书");
        return m;
    }

    /**
     * 借阅授权卡的参数明细：列出 ISBN（唯一标识）与书名，便于用户核对具体版本。
     */
    private List<Map<String, String>> borrowRows(String bookTitle, String isbn) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("书名", bookTitle));
        if (isbn != null && !isbn.isBlank()) {
            rows.add(row("ISBN", isbn));
        }
        return rows;
    }

    /** 定位结果：唯一命中时 book 非空；书名匹配到多本时 candidates 非空且 book 为 null */
    private record BookMatch(Book book, List<Book> candidates) {}

    /**
     * 按 ISBN 或书名定位目标图书。
     * ISBN 唯一，优先；同时传了书名则做一致性校验，不一致直接中止，避免两个参数指向不同的书。
     * 只按书名匹配时，命中多本一律返回候选，不替用户挑。
     */
    private BookMatch locateBook(String bookTitle, String isbn) {
        String title = blankToNull(bookTitle);
        String isbnKey = blankToNull(isbn);

        if (isbnKey != null) {
            Book byIsbn = ctx.bookMapper.selectByIsbn(isbnKey);
            if (byIsbn == null) {
                throw new BusinessException(400, "未找到 ISBN 为 " + isbnKey + " 的图书，请重新调用 queryBooks 核对 ISBN");
            }
            if (title != null && !titlesCompatible(title, byIsbn.getTitle())) {
                throw new BusinessException(400, "书名为「" + title + "」，但 ISBN " + isbnKey + " 对应的是《"
                        + byIsbn.getTitle() + "》，两者不一致，已中止办理；请重新调用 queryBooks 获取正确书目信息");
            }
            return new BookMatch(byIsbn, List.of());
        }

        if (title == null) {
            throw new BusinessException(400, "缺少书名参数，请重新调用 queryBooks 获取书名后再办理");
        }
        List<Book> candidates = ctx.bookMapper.selectByTitleLike(title);
        if (candidates.isEmpty()) {
            throw new BusinessException(400, "未找到书名包含「" + title + "」的在架图书，请重新调用 queryBooks 确认书名");
        }
        if (candidates.size() == 1) {
            return new BookMatch(candidates.get(0), List.of());
        }
        return new BookMatch(null, candidates);
    }

    /** 书名匹配到多本时返回候选清单，交由用户选定；不抛异常，便于模型读取候选并转述 */
    private Map<String, Object> ambiguousBorrow(String bookTitle, List<Book> candidates) {
        List<Map<String, Object>> items = new ArrayList<>();
        int limit = Math.min(candidates.size(), BORROW_CANDIDATE_LIMIT);
        for (int i = 0; i < limit; i++) {
            Book b = candidates.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", b.getTitle());
            m.put("author", b.getAuthor());
            m.put("publisher", b.getPublisher());
            m.put("isbn", b.getIsbn());
            m.put("availableCount", b.getAvailableCount());
            items.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("ambiguous", true);
        m.put("message", "书名「" + bookTitle + "」匹配到 " + candidates.size() + " 本图书，未办理借阅");
        m.put("candidates", items);
        m.put("nextAction", "把 candidates 的书名与出版社念给用户，请用户明确选定一本后，"
                + "用该书名（或对应 isbn）重新调用 applyBorrow；不要自行挑选");
        return m;
    }

    /** 工具卡片展示用：把用户确认的书名摆到卡片上，错位时可当场发现 */
    private String borrowDetail(String bookTitle, String isbn) {
        String title = blankToNull(bookTitle);
        String isbnKey = blankToNull(isbn);
        StringBuilder sb = new StringBuilder("书名：");
        sb.append(title == null ? "（未提供）" : "《" + title + "》");
        if (isbnKey != null) {
            sb.append("，ISBN：").append(isbnKey);
        }
        return sb.toString();
    }

    /** 书名一致性：归一化（去空白、全角空格与书名号）后互为包含即视为同一本，容忍副标题省略 */
    private boolean titlesCompatible(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        return !na.isEmpty() && !nb.isEmpty() && (na.contains(nb) || nb.contains(na));
    }

    private String normalizeTitle(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000《》]", "");
    }

    /**
     * 续借本人借阅中的图书（写操作）。
     */
    @Tool("【续借·写操作】当用户表达续借/延长借期意图时，应调用本工具提交续借申请。"
            + "系统会在执行前弹出授权卡，由用户在卡片上确认后才真正生效——你无需先向用户索要确认，直接调用即可。"
            + "仅状态为「借阅中」且续借次数未用完的记录可续借；成功后返回新的应还时间")
    public Map<String, Object> renewMyBorrow(@P("借阅码（如 BR202609090001），来自 queryMyBorrows 返回，禁止猜测") String borrowNo) {
        return withWriteEvent("renewMyBorrow", "续借 " + borrowNo,
                List.of(row("借阅码", borrowNo)),
                Map.of("borrowNo", borrowNo == null ? "" : borrowNo),
                v -> doRenew(borrowNo));
    }

    /** 执行已确认的续借（授权卡确认后由 {@link AiService} 调用，绕过门禁） */
    public Map<String, Object> renewMyBorrowConfirmed(String borrowNo) {
        return doRenew(borrowNo);
    }

    /** 续借的实际业务逻辑（门禁与确认后执行共用） */
    private Map<String, Object> doRenew(String borrowNo) {
        Borrow b = ctx.borrowService.renew(userId, userType, null, borrowNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("borrowNo", b.getBorrowNo());
        m.put("bookTitle", b.getBookTitle());
        m.put("newDueTime", str(b.getDueTime()));
        m.put("renewCount", b.getRenewCount());
        m.put("renewMaxCount", b.getRenewMaxCount());
        m.put("message", "续借成功，已更新应还时间");
        return m;
    }

    /**
     * 发起归还申请（写操作）。
     */
    @Tool("【归还申请·写操作】当用户表达归还/还书意图时，应调用本工具提交归还申请。"
            + "系统会在执行前弹出授权卡，由用户在卡片上确认后才真正生效——你无需先向用户索要确认，直接调用即可。"
            + "状态为「借阅中」或「已逾期」的图书提交后进入「待确认归还」，"
            + "需将图书送至服务台由管理员确认后完成归还")
    public Map<String, Object> requestReturn(@P("借阅码（如 BR202609090001），来自 queryMyBorrows 返回，禁止猜测") String borrowNo) {
        return withWriteEvent("requestReturn", "归还 " + borrowNo,
                List.of(row("借阅码", borrowNo)),
                Map.of("borrowNo", borrowNo == null ? "" : borrowNo),
                v -> doReturn(borrowNo));
    }

    /** 执行已确认的归还申请（授权卡确认后由 {@link AiService} 调用，绕过门禁） */
    public Map<String, Object> requestReturnConfirmed(String borrowNo) {
        return doReturn(borrowNo);
    }

    /** 归还申请的实际业务逻辑（门禁与确认后执行共用） */
    private Map<String, Object> doReturn(String borrowNo) {
        Borrow b = ctx.borrowService.returnBook(userId, userType, null, borrowNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("borrowNo", b.getBorrowNo());
        m.put("bookTitle", b.getBookTitle());
        m.put("status", statusLabel(b.getStatus()));
        m.put("message", "归还申请已提交，请将图书送至服务台由管理员确认");
        return m;
    }
}
