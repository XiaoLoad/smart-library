package com.library.service;

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
     */
    @Tool("为当前用户提交图书借阅申请（写操作）。bookId 必须来自 queryBooks 返回的 id，禁止猜测。"
            + "调用本工具前必须已向用户确认具体书名且用户明确回复同意（如「确认」「借吧」）；"
            + "成功后返回借阅码和取书截止时间，需提醒用户在截止时间前到服务台取书")
    public Map<String, Object> applyBorrow(@P("图书ID，必须来自 queryBooks 返回的 id") Long bookId) {
        return withEvent("applyBorrow", "图书ID：" + bookId, v -> {
            Borrow b = ctx.borrowService.apply(userId, bookId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("borrowNo", b.getBorrowNo());
            m.put("bookTitle", b.getBookTitle());
            m.put("status", statusLabel(b.getStatus()));
            m.put("pickupDeadline", str(b.getPickupDeadline()));
            m.put("message", "借阅申请已提交，请在取书截止时间前到服务台取书");
            return m;
        });
    }

    /**
     * 续借本人借阅中的图书（写操作）。
     */
    @Tool("为当前用户续借本人借阅中的图书（写操作）。仅状态为「借阅中」且续借次数未用完的记录可续借。"
            + "调用前必须向用户确认书名/借阅码并获得明确同意；成功后返回新的应还时间")
    public Map<String, Object> renewMyBorrow(@P("借阅码（如 BR202609090001），来自 queryMyBorrows 返回，禁止猜测") String borrowNo) {
        return withEvent("renewMyBorrow", "借阅码：" + borrowNo, v -> {
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
        });
    }

    /**
     * 发起归还申请（写操作）。
     */
    @Tool("为当前用户发起图书归还申请（写操作）。状态为「借阅中」或「已逾期」的图书提交后进入「待确认归还」，"
            + "需将图书送至服务台由管理员确认后完成归还。调用前必须向用户确认书名/借阅码并获得明确同意")
    public Map<String, Object> requestReturn(@P("借阅码（如 BR202609090001），来自 queryMyBorrows 返回，禁止猜测") String borrowNo) {
        return withEvent("requestReturn", "借阅码：" + borrowNo, v -> {
            Borrow b = ctx.borrowService.returnBook(userId, userType, null, borrowNo);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("borrowNo", b.getBorrowNo());
            m.put("bookTitle", b.getBookTitle());
            m.put("status", statusLabel(b.getStatus()));
            m.put("message", "归还申请已提交，请将图书送至服务台由管理员确认");
            return m;
        });
    }
}
