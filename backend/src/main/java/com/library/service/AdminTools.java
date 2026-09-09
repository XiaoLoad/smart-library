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
                    // 先同步超期状态，保证统计口径与列表页一致
                    ctx.borrowService.syncBorrowStatus();
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
    @Tool("【管理员】查询全馆运营统计概览：馆藏图书总量、在册读者数、当前借阅中、逾期未还、"
            + "近6个月借阅/归还趋势、热门图书借阅排行 Top5、图书分类借阅分布。"
            + "用于回答「馆里有多少书/多少读者/多少人在借/逾期情况/最热门的书/借阅趋势」类问题")
    public Map<String, Object> adminQueryStats() {
        return withEvent("adminQueryStats", null, v -> {
            Map<String, Object> o = ctx.statsService.overview(3, null);
            Map<String, Object> m = new LinkedHashMap<>();

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
            m.put("categoryDist", o.get("categoryDist"));
            return m;
        });
    }

    /**
     * 柜台统一办理（管理员，写操作）：按记录状态自动分流。
     */
    @Tool("【管理员】柜台统一办理一条借阅记录（写操作）：按记录当前状态自动分流——"
            + "待取书(0)→取书确认，借阅中(1)/已逾期(3)→直接归还并自动结算逾期罚款，待确认归还(5)→归还确认。"
            + "调用前必须与来人核对借阅码并获得管理员明确确认，借阅码必须来自实际核对或 adminQueryBorrows 查询结果，禁止猜测；"
            + "返回办理动作（pickup=取书/return=归还/confirm=归还确认）和办理结果")
    public Map<String, Object> adminCounterProcess(@P("借阅码（如 BR202609090001），必须实际核对，禁止猜测") String borrowNo) {
        String no = blankToNull(borrowNo);
        return withEvent("adminCounterProcess", "借阅码：" + no, v -> {
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
        });
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
