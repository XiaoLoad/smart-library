package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.dto.PickupResultVo;
import com.library.entity.Borrow;
import com.library.entity.Fine;
import com.library.entity.User;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员工具（仅 userType=3 时注册，普通读者的模型上下文中不可见）：
 * 全馆借阅/罚款/读者查询、运营统计概览、柜台统一办理。
 */
public class AdminTools extends BaseLibraryTools {

    public AdminTools(Long userId, Integer userType, AiToolContext ctx) {
        super(userId, userType, ctx);
    }

    /**
     * 查询全馆借阅记录（管理员）。
     */
    @Tool("【管理员】查询全馆所有读者的借阅记录，可按状态过滤（0=待取书,1=借阅中,2=已归还,3=已逾期,4=已取消,5=待确认归还），"
            + "可按借阅码/书名/读者姓名关键词搜索；状态不传或传 all 表示全部，最多返回 20 条")
    public Map<String, Object> adminQueryBorrows(@P("借阅状态：0/1/2/3/4/5 或 all，可省略") String status,
                                                 @P("搜索关键词：借阅码/书名/读者姓名片段，可省略") String search) {
        return withEvent("adminQueryBorrows",
                "状态：" + (status == null || status.isBlank() ? "全部" : status)
                        + (search != null && !search.isBlank() ? "，关键词：" + search : ""),
                v -> {
                    // 超期状态由 BorrowService 定时任务统一同步，读工具不触发写
                    List<Borrow> list = ctx.borrowMapper.selectList(null, true,
                            parseStatus(status), blankToNull(search));
                    int limit = Math.min(list.size(), 20);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < limit; i++) {
                        Borrow b = list.get(i);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("borrowNo", b.getBorrowNo());
                        m.put("userName", b.getUserName());
                        m.put("bookTitle", b.getBookTitle());
                        m.put("status", statusLabel(b.getStatus()));
                        m.put("applyTime", str(b.getApplyTime()));
                        m.put("borrowTime", str(b.getBorrowTime()));
                        m.put("dueTime", str(b.getDueTime()));
                        m.put("returnTime", str(b.getReturnTime()));
                        m.put("remark", b.getRemark());
                        items.add(m);
                    }
                    return result(list.size(), items);
                });
    }

    /**
     * 查询全馆罚款记录（管理员）。
     */
    @Tool("【管理员】查询全馆所有读者的罚款记录，可按状态过滤（0=未缴,1=已缴），不传为全部，最多返回 20 条")
    public Map<String, Object> adminQueryFines(@P("罚款状态：0=未缴,1=已缴,all=全部，可省略") String status) {
        return withEvent("adminQueryFines", "状态：" + (status == null || status.isBlank() ? "全部" : status), v -> {
            Integer statusInt = parseStatus(status);
            List<Fine> list = ctx.fineMapper.selectList(null, true);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Fine f : list) {
                if (statusInt != null && (f.getStatus() == null || f.getStatus() != statusInt)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("userName", f.getUserName());
                m.put("borrowNo", f.getBorrowNo());
                m.put("bookTitle", f.getBookTitle());
                m.put("amount", f.getAmount() == null ? null : f.getAmount().toPlainString());
                m.put("daysOverdue", f.getDaysOverdue());
                m.put("status", fineStatusLabel(f.getStatus()));
                m.put("createTime", str(f.getCreateTime()));
                items.add(m);
            }
            return result(items.size(), items);
        });
    }

    /**
     * 查询读者名录（管理员）。
     */
    @Tool("【管理员】查询读者名录（教师/学生，不含管理员账号），可按姓名/学号/工号/登录账号关键词搜索，最多返回 15 条")
    public Map<String, Object> adminQueryUsers(@P("搜索关键词：姓名/学号/工号/登录账号片段，可省略") String keyword) {
        return withEvent("adminQueryUsers", "关键词：" + (keyword == null || keyword.isBlank() ? "无" : keyword), v -> {
            List<User> list = ctx.userMapper.selectList(blankToNull(keyword));
            List<Map<String, Object>> items = new ArrayList<>();
            for (User u : list) {
                // 仅返回读者（教师/学生），排除管理员
                if (u.getUserType() == null || u.getUserType() == 3) {
                    continue;
                }
                if (items.size() >= 15) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("realName", u.getRealName());
                m.put("username", u.getUsername());
                m.put("role", userTypeLabel(u.getUserType()));
                m.put("studentNo", u.getStudentNo());
                m.put("teacherNo", u.getTeacherNo());
                m.put("department", u.getDepartment());
                m.put("className", u.getClassName());
                m.put("major", u.getMajor());
                m.put("status", u.getStatus() != null && u.getStatus() == 1 ? "正常" : "禁用");
                items.add(m);
            }
            return result(items.size(), items);
        });
    }

    /**
     * 全馆运营统计概览（管理员）。
     */
    @Tool("【管理员】查询全馆运营统计概览，返回："
            + "collection=馆藏总量（titles=图书种数，copies=总册数，availableCopies=当前可借册数）；"
            + "categoryBookDist=各一级分类的【藏书】分布（name/titles 种数/copies 册数/availableCopies 可借册数，"
            + "回答「某类有多少书/馆藏分类统计」必须用本字段）；"
            + "operations=今日运营与待办（pendingPickup=待取书单数，pendingReturnConfirm=归还待确认单数，"
            + "overdue=逾期未还本数，outsideCount=馆外流通本数，todayBorrow=今日借出本数，"
            + "monthNewBooks=近30天新入藏图书，activeReaders30d=近30天活跃读者数，"
            + "unpaidFineAmount=未缴罚款总额元，activeStatusDist=在办事务状态分布）；"
            + "metrics=指标卡片（馆藏/读者/馆外流通/逾期/今日借出/未缴罚款）；"
            + "categoryBorrowDist=各一级分类的【真实借阅次数】分布（这是实际取书的借还流水次数，不是藏书量，禁止当藏书数播报）；"
            + "borrowTrend=近6个月借出/归还趋势（借出按实际取书时间）；hotBooks=热门图书排行 Top8（按实际取书次数）。"
            + "用于回答「馆里有多少书/各分类多少书/多少读者/多少书在外面/逾期情况/今天借出多少/有多少待办/多少罚款/最热门的书/借阅趋势」类问题")
    public Map<String, Object> adminQueryStats() {
        return withEvent("adminQueryStats", null, v -> {
            Map<String, Object> o = ctx.statsService.overview(3, null);
            Map<String, Object> m = new LinkedHashMap<>();

            // 馆藏总量（种/册/可借）
            m.put("collection", o.get("collection"));
            // 各一级分类【藏书】分布
            m.put("categoryBookDist", o.get("categoryBookDist"));
            // 今日运营与待办
            m.put("operations", o.get("operations"));

            // 指标卡片 → 扁平指标
            Map<String, Object> metrics = new LinkedHashMap<>();
            Object statsObj = o.get("stats");
            if (statsObj instanceof List<?> cards) {
                for (Object card : cards) {
                    if (card instanceof Map<?, ?> c) {
                        Map<String, Object> metric = new LinkedHashMap<>();
                        metric.put("label", c.get("label"));
                        metric.put("value", c.get("value"));
                        metric.put("unit", c.get("unit"));
                        metric.put("note", c.get("trend"));
                        metrics.put(str(c.get("key")), metric);
                    }
                }
            }
            m.put("metrics", metrics);
            m.put("borrowTrend", o.get("borrowTrend"));
            m.put("hotBooks", o.get("hotBooks"));
            // 借阅次数分布（非藏书量）
            m.put("categoryBorrowDist", o.get("categoryDist"));
            return m;
        });
    }

    /**
     * 柜台统一办理（管理员，写操作）：按记录状态自动分流。
     */
    @Tool("【管理员】柜台统一办理一条借阅记录（写操作）：按记录当前状态自动分流——"
            + "待取书(0)→取书确认，借阅中(1)/已逾期(3)→直接归还并自动结算逾期罚款，待确认归还(5)→归还确认。"
            + "当管理员要办理某条借阅记录时，应调用本工具；系统会在执行前弹出授权卡，"
            + "由管理员在卡片上确认后才真正生效——你无需先向管理员索要确认，直接调用即可。"
            + "借阅码必须来自实际核对或 adminQueryBorrows 查询结果，禁止猜测；"
            + "返回办理动作（pickup=取书/return=归还/confirm=归还确认）和办理结果")
    public Map<String, Object> adminCounterProcess(@P("借阅码（如 BR202609090001），必须实际核对，禁止猜测") String borrowNo) {
        String no = blankToNull(borrowNo);
        return withWriteEvent("adminCounterProcess", "柜台办理 " + no,
                List.of(row("借阅码", no)),
                Map.of("borrowNo", no == null ? "" : no),
                v -> doCounterProcess(no));
    }

    /** 执行已确认的柜台办理（授权卡确认后由 {@link AiService} 调用，绕过门禁） */
    public Map<String, Object> adminCounterProcessConfirmed(String borrowNo) {
        return doCounterProcess(blankToNull(borrowNo));
    }

    /**
     * 批量柜台办理（管理员，写操作）：一次授权处理多条借阅记录。
     * <p>
     * 底层直接复用 {@code BorrowService.batchProcess}（已支持批量、事务内逐项分流）。
     * 适用场景：管理员说「把待取书的都确认取书」「这些单子一起归还确认」——
     * 相比逐条调用 adminCounterProcess，本工具一次授权完成整批，避免多张授权卡逐个点头。
     * 借阅码必须来自 adminQueryBorrows 查询结果，禁止猜测。
     */
    @Tool("【管理员】批量柜台办理多条借阅记录（写操作）：一次授权处理一批，按每条记录当前状态自动分流"
            + "——待取书(0)→取书确认，借阅中(1)/已逾期(3)→直接归还并自动结算逾期罚款，待确认归还(5)→归还确认。"
            + "当管理员要批量办理（如「把待取书的都确认」「待确认归还的全归」）时优先调用本工具而不是逐条办理；"
            + "系统会弹出一张批量授权卡列出全部明细，管理员确认后整批生效。"
            + "借阅码列表必须来自 adminQueryBorrows 查询结果，禁止猜测；返回逐项办理结果与成功/失败计数")
    public Map<String, Object> adminCounterProcessBatch(
            @P("借阅码列表（来自 adminQueryBorrows 查询结果，逗号分隔，如 BR202609090001,BR202609090002）")
            String borrowNos) {
        List<String> nos = new ArrayList<>();
        if (borrowNos != null && !borrowNos.isBlank()) {
            for (String s : borrowNos.split("[,，;；\\s]+")) {
                if (!s.isBlank()) {
                    nos.add(s.trim());
                }
            }
        }
        if (nos.isEmpty()) {
            throw new BusinessException("请提供至少一个借阅码");
        }
        // 授权卡明细：每条一行「借阅码 → 书名」需要先查库，但门禁拦截阶段不应产生查询副作用之外的动作；
        // 直接以借阅码列表作为明细（确认后执行时返回逐项含书名的结果）
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("记录数", String.valueOf(nos.size())));
        rows.add(row("借阅码", String.join("、", nos)));
        return withWriteEvent("adminCounterProcessBatch", "批量柜台办理（" + nos.size() + " 条）",
                rows,
                Map.of("borrowNos", String.join(",", nos)),
                v -> doCounterBatch(nos));
    }

    /** 执行已确认的批量柜台办理（批量授权卡确认后由 {@link AiService} 调用，绕过门禁） */
    public Map<String, Object> adminCounterProcessBatchConfirmed(String borrowNos) {
        List<String> nos = new ArrayList<>();
        if (borrowNos != null && !borrowNos.isBlank()) {
            for (String s : borrowNos.split("[,，;；\\s]+")) {
                if (!s.isBlank()) {
                    nos.add(s.trim());
                }
            }
        }
        return doCounterBatch(nos);
    }

    /** 批量柜台办理的实际业务逻辑（门禁与确认后执行共用） */
    private Map<String, Object> doCounterBatch(List<String> nos) {
        if (nos.isEmpty()) {
            throw new BusinessException("请提供至少一个借阅码");
        }
        PickupResultVo r = ctx.borrowService.batchProcess(null, nos, userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", r.getFailCount() == 0);
        m.put("total", r.getSuccessCount() + r.getFailCount());
        m.put("successCount", r.getSuccessCount());
        m.put("failCount", r.getFailCount());
        m.put("results", r.getResults());
        m.put("message", "批量办理完成：成功 " + r.getSuccessCount() + " 条，失败 " + r.getFailCount() + " 条");
        return m;
    }

    /** 柜台办理的实际业务逻辑（门禁与确认后执行共用） */
    private Map<String, Object> doCounterProcess(String no) {
        if (no == null) {
            throw new BusinessException("请提供借阅码");
        }
        PickupResultVo r = ctx.borrowService.batchProcess(null, List.of(no), userId);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<?, ?> first = r.getResults().isEmpty() ? null : (Map<?, ?>) r.getResults().get(0);
        if (r.getSuccessCount() == 1 && first != null) {
            m.put("success", true);
            m.put("borrowNo", first.get("borrowNo"));
            m.put("bookTitle", first.get("bookTitle"));
            m.put("userName", first.get("userName"));
            m.put("action", actionLabel(str(first.get("action"))));
            m.put("message", actionMessage(str(first.get("action"))));
        } else {
            m.put("success", false);
            m.put("borrowNo", no);
            m.put("message", first != null ? str(first.get("message")) : "办理失败");
        }
        return m;
    }

    private String actionLabel(String action) {
        if (action == null) return "办理";
        return switch (action) {
            case "pickup" -> "取书确认";
            case "return" -> "直接归还";
            case "confirm" -> "归还确认";
            default -> action;
        };
    }

    private String actionMessage(String action) {
        if (action == null) return "办理完成";
        return switch (action) {
            case "pickup" -> "取书成功，已进入借阅中";
            case "return" -> "归还成功，库存已更新（如有逾期罚款已自动生成）";
            case "confirm" -> "归还确认成功，库存已更新（如有逾期罚款已自动生成）";
            default -> "办理完成";
        };
    }
}
