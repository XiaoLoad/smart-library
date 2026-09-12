package com.library.service;

import com.library.entity.Announcement;
import com.library.entity.Book;
import com.library.entity.Category;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用工具（读者与管理员均注册）：分类检索、馆藏图书检索、馆内公告。
 */
public class CommonTools extends BaseLibraryTools {

    public CommonTools(Long userId, Integer userType, AiToolContext ctx) {
        super(userId, userType, ctx);
    }

    /**
     * 查询馆内图书分类（扁平列表，仅含启用状态）。
     * 用户按分类名找书时，模型必须先调本工具取分类 ID，再调 queryBooks 传 categoryId。
     */
    @Tool("查询馆内图书分类列表（返回分类ID、名称、父分类ID、层级、藏书量；parentId=0 为一级分类，藏书量含全部子分类）。"
            + "当用户按分类/类型描述找书时（如「计算机类的书」「哲学类小说」），必须先调用本工具获取分类ID，"
            + "再调用 queryBooks 并传入 categoryId（会自动包含该分类下所有子分类），不要仅凭关键词猜测分类")
    public Map<String, Object> queryCategories() {
        return withEvent("queryCategories", null, v -> {
            List<Category> tree = ctx.categoryService.categoryTree();
            List<Map<String, Object>> items = new ArrayList<>();
            flattenEnabled(tree, items);
            return result(items.size(), items);
        });
    }

    /** 深度优先展开启用状态的分类（禁用分类连同其子树一并跳过） */
    private void flattenEnabled(List<Category> nodes, List<Map<String, Object>> out) {
        for (Category c : nodes) {
            if (c.getStatus() != null && c.getStatus() == 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getCategoryName());
            m.put("parentId", c.getParentId());
            m.put("level", c.getLevel());
            m.put("bookCount", c.getBookCount());
            out.add(m);
            if (c.getChildren() != null) {
                flattenEnabled(c.getChildren(), out);
            }
        }
    }

    /**
     * 按条件检索馆藏图书（最多 15 条）。
     */
    @Tool("按条件检索馆藏图书，最多返回 15 条。支持：关键词（匹配书名/作者/ISBN）、"
            + "分类ID（来自 queryCategories，自动包含其全部子分类）、是否只看可借。"
            + "三个参数均可省略；不传任何条件时返回最新入库图书。"
            + "返回字段含 description=图书内容简介，用户要求介绍/推荐某本书时应据此概述其内容；"
            + "用户确认借阅某本时，须记下该书返回的 title 与 isbn，调用 applyBorrow 时一并传入，确保借出的正是这一本")
    public Map<String, Object> queryBooks(@P("检索关键词：书名/作者/ISBN 片段，可省略") String keyword,
                                          @P("分类ID，必须来自 queryCategories 返回的 id；传该ID自动包含所有子分类，可省略") Long categoryId,
                                          @P("是否只看当前可借的书：true=只看可借数量>0，false 或省略=全部") Boolean availableOnly) {
        String kw = blankToNull(keyword);
        String startDetail = "关键词：" + (kw == null ? "无" : kw)
                + (categoryId != null ? "，分类ID：" + categoryId : "")
                + (Boolean.TRUE.equals(availableOnly) ? "，仅可借" : "");
        return withEvent("queryBooks", startDetail, v -> {
            List<Long> categoryIds = null;
            if (categoryId != null) {
                categoryIds = ctx.categoryService.collectDescendantIds(categoryId);
            }
            List<Book> list = ctx.bookMapper.selectList(kw, categoryIds);
            // 分类 ID → 名称映射，用于结果展示
            Map<Long, String> cateNames = new LinkedHashMap<>();
            for (Category c : ctx.categoryMapper.selectAll()) {
                cateNames.put(c.getId(), c.getCategoryName());
            }
            List<Book> filtered = new ArrayList<>();
            for (Book b : list) {
                if (Boolean.TRUE.equals(availableOnly)
                        && (b.getAvailableCount() == null || b.getAvailableCount() <= 0)) {
                    continue;
                }
                filtered.add(b);
            }
            int limit = Math.min(filtered.size(), 15);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                Book b = filtered.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.getId());
                m.put("title", b.getTitle());
                m.put("author", b.getAuthor());
                m.put("publisher", b.getPublisher());
                m.put("isbn", b.getIsbn());
                m.put("category", b.getCategoryId() == null ? null : cateNames.get(b.getCategoryId()));
                m.put("location", b.getLocation());
                m.put("description", b.getDescription());
                m.put("availableCount", b.getAvailableCount());
                m.put("totalCount", b.getTotalCount());
                m.put("available", b.getAvailableCount() != null && b.getAvailableCount() > 0);
                items.add(m);
            }
            return result(filtered.size(), items);
        });
    }

    /**
     * 全馆热门图书借阅排行（聚合公开数据，读者也可查）。
     */
    @Tool("查询全馆热门图书借阅排行榜 Top5（全馆聚合数据，不含任何读者个人信息），"
            + "用于回答「最近借得最多的书/最热门的书/大家都在借什么书」类问题")
    public Map<String, Object> queryHotBooks() {
        return withEvent("queryHotBooks", null, v -> {
            Map<String, Object> o = ctx.statsService.overview(3, null);
            List<Map<String, Object>> items = new ArrayList<>();
            Object hot = o.get("hotBooks");
            if (hot instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> b) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("rank", b.get("rank"));
                        m.put("title", b.get("title"));
                        m.put("author", b.get("author"));
                        m.put("borrowCount", b.get("count"));
                        items.add(m);
                    }
                }
            }
            return result(items.size(), items);
        });
    }

    /**
     * 查询馆内有效公告（最多 5 条）。
     */
    @Tool("查询馆内最新公告，最多返回 5 条")
    public Map<String, Object> queryAnnouncements() {
        return withEvent("queryAnnouncements", null, v -> {
            List<Announcement> list = ctx.announcementMapper.selectList();
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
}
