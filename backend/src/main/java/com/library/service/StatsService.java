package com.library.service;

import com.library.entity.Book;
import com.library.entity.Borrow;
import com.library.entity.Category;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowMapper;
import com.library.mapper.CategoryMapper;
import com.library.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计服务：仪表盘首页与 AI 管理员工具共用同一数据口径。
 * <p>
 * 返回结构：
 * <ul>
 *   <li>stats: 卡片数组 [{ key, label, value, unit, icon, trend, danger? }]</li>
 *   <li>borrowTrend: { months: [], borrow: [], return: [] }（近 6 个月）</li>
 *   <li>hotBooks: [{ rank, title, author, count }]（借阅次数 Top5）</li>
 *   <li>categoryDist: [{ name, value }]（按一级分类分布）</li>
 * </ul>
 */
@Service
public class StatsService {

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private BorrowMapper borrowMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 统计概览。
     *
     * @param type          用户类型：3=管理员（全局口径），其他=个人口径
     * @param currentUserId 个人口径时的当前用户 ID；管理员口径可传 null
     */
    public Map<String, Object> overview(int type, Long currentUserId) {
        List<Book> allBooks = bookMapper.selectList(null, null);
        List<Borrow> allBorrows = borrowMapper.selectList(null, true, null, null);
        List<User> allUsers = userMapper.selectAll();
        List<Category> categories = categoryMapper.selectAll();

        // 按角色过滤借阅记录
        List<Borrow> myBorrows = type == 3 ? allBorrows : allBorrows.stream()
                .filter(b -> b.getUserId() != null && b.getUserId().equals(currentUserId))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("stats", buildStats(type, allBooks, allUsers, allBorrows, myBorrows));
        data.put("borrowTrend", buildBorrowTrend(type, allBorrows, myBorrows));
        data.put("hotBooks", buildHotBooks(type, allBorrows, myBorrows, allBooks));
        // 注意：categoryDist 是「借阅次数」分布（基于借阅记录），不是藏书量
        data.put("categoryDist", buildCategoryDist(type, allBorrows, myBorrows, allBooks, categories));
        // 馆藏口径数据：分类藏书分布（基于图书）+ 馆藏总量汇总
        data.put("categoryBookDist", buildCategoryBookDist(allBooks, categories));
        data.put("collection", buildCollectionSummary(allBooks));
        return data;
    }

    /**
     * 馆藏总量汇总：种数（图书记录数）、册数（totalCount 之和）、可借册数。
     */
    private Map<String, Object> buildCollectionSummary(List<Book> allBooks) {
        long copies = allBooks.stream()
                .mapToLong(b -> b.getTotalCount() == null ? 0 : b.getTotalCount()).sum();
        long available = allBooks.stream()
                .mapToLong(b -> b.getAvailableCount() == null ? 0 : b.getAvailableCount()).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titles", allBooks.size());
        m.put("copies", copies);
        m.put("availableCopies", available);
        return m;
    }

    /**
     * 构建馆藏分类分布（按一级分类统计图书种数与册数，基于馆藏图书而非借阅记录）。
     * 未挂分类的图书归入「未分类」。
     */
    private List<Map<String, Object>> buildCategoryBookDist(List<Book> allBooks, List<Category> categories) {
        Map<Long, String> cateNames = categories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
        // rootId -> [种数, 册数, 可借册数]；-1 表示未分类
        Map<Long, long[]> agg = new HashMap<>();
        for (Book b : allBooks) {
            Long rootId = (b.getCategoryId() == null)
                    ? -1L : findRootCategory(categories, b.getCategoryId());
            long[] a = agg.computeIfAbsent(rootId, k -> new long[3]);
            a[0] += 1;
            a[1] += b.getTotalCount() == null ? 0 : b.getTotalCount();
            a[2] += b.getAvailableCount() == null ? 0 : b.getAvailableCount();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey() == -1L ? "未分类" : cateNames.getOrDefault(e.getKey(), "其他"));
            m.put("titles", e.getValue()[0]);
            m.put("copies", e.getValue()[1]);
            m.put("availableCopies", e.getValue()[2]);
            result.add(m);
        }
        result.sort((x, y) -> Long.compare((Long) y.get("copies"), (Long) x.get("copies")));
        return result;
    }

    /**
     * 构建统计卡片数组
     */
    private List<Map<String, Object>> buildStats(int type, List<Book> allBooks, List<User> allUsers,
                                                  List<Borrow> allBorrows, List<Borrow> myBorrows) {
        List<Map<String, Object>> stats = new ArrayList<>();
        if (type == 3) {
            // 管理员：全局统计（藏书主指标用总册数，副标注种数）
            long totalCopies = allBooks.stream()
                    .mapToLong(b -> b.getTotalCount() == null ? 0 : b.getTotalCount()).sum();
            stats.add(statItem("books", "馆藏图书", totalCopies, "册", "Reading", "共 " + allBooks.size() + " 种"));
            stats.add(statItem("users", "在册读者", allUsers.stream().filter(u -> u.getStatus() == 1).count(), "人", "User", "+" + allUsers.size() + " 注册"));
            stats.add(statItem("borrowing", "借阅中", allBorrows.stream().filter(b -> b.getStatus() == 1).count(), "本", "Tickets", "实时"));
            long overdue = allBorrows.stream().filter(b -> b.getStatus() == 3).count();
            stats.add(statItem("overdue", "逾期未还", overdue, "本", "Warning", overdue > 0 ? "待处理" : "无逾期", overdue > 0));
        } else {
            // 教师/学生：个人统计
            long borrowing = myBorrows.stream().filter(b -> b.getStatus() == 1).count();
            long overdue = myBorrows.stream().filter(b -> b.getStatus() == 3).count();
            long dueSoon = myBorrows.stream().filter(b -> {
                if (b.getStatus() == 1 && b.getDueTime() != null) {
                    return b.getDueTime().isBefore(LocalDateTime.now().plusDays(7));
                }
                return false;
            }).count();
            stats.add(statItem("borrowing", "我的借阅中", borrowing, "本", "Tickets",
                    dueSoon > 0 ? dueSoon + " 本即将到期" : "无即将到期"));
            stats.add(statItem("history", "累计借阅", myBorrows.size(), "本", "Reading", "历史总计"));
            stats.add(statItem("dueSoon", "即将到期", dueSoon, "本", "Warning",
                    dueSoon > 0 ? "7 天内" : "无", dueSoon > 0));
            stats.add(statItem("fine", "待缴罚款", 0, "元", "Warning", "无欠款"));
        }
        return stats;
    }

    private Map<String, Object> statItem(String key, String label, Object value, String unit, String icon, String trend) {
        return statItem(key, label, value, unit, icon, trend, false);
    }

    private Map<String, Object> statItem(String key, String label, Object value, String unit, String icon, String trend, boolean danger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("icon", icon);
        m.put("trend", trend);
        m.put("danger", danger);
        return m;
    }

    /**
     * 构建借阅趋势（近 6 个月）
     */
    private Map<String, Object> buildBorrowTrend(int type, List<Borrow> allBorrows, List<Borrow> myBorrows) {
        List<Borrow> trendBorrows = type == 3 ? allBorrows : myBorrows;
        List<String> months = new ArrayList<>();
        List<Long> borrow = new ArrayList<>();
        List<Long> returnList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 5; i >= 0; i--) {
            LocalDateTime start = now.minusMonths(i).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            LocalDateTime end = start.plusMonths(1);
            months.add(start.getMonthValue() + "月");
            borrow.add(trendBorrows.stream()
                    .filter(b -> b.getApplyTime() != null && b.getApplyTime().isAfter(start) && b.getApplyTime().isBefore(end))
                    .count());
            returnList.add(trendBorrows.stream()
                    .filter(b -> b.getReturnTime() != null && b.getReturnTime().isAfter(start) && b.getReturnTime().isBefore(end))
                    .count());
        }
        Map<String, Object> trend = new HashMap<>();
        trend.put("months", months);
        trend.put("borrow", borrow);
        trend.put("return", returnList);
        return trend;
    }

    /**
     * 构建热门图书（借阅次数 Top5）
     */
    private List<Map<String, Object>> buildHotBooks(int type, List<Borrow> allBorrows, List<Borrow> myBorrows, List<Book> allBooks) {
        List<Borrow> trendBorrows = type == 3 ? allBorrows : myBorrows;
        Map<Long, Long> bookCount = new HashMap<>();
        Map<Long, Book> bookMap = allBooks.stream().collect(Collectors.toMap(Book::getId, b -> b));
        for (Borrow b : trendBorrows) {
            bookCount.merge(b.getBookId(), 1L, Long::sum);
        }
        List<Map.Entry<Long, Long>> sorted = bookCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toList());
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<Long, Long> e : sorted) {
            Book book = bookMap.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("title", book != null ? book.getTitle() : "未知");
            m.put("author", book != null ? book.getAuthor() : "");
            m.put("count", e.getValue());
            result.add(m);
        }
        return result;
    }

    /**
     * 构建分类分布（按一级分类汇总借阅次数）
     */
    private List<Map<String, Object>> buildCategoryDist(int type, List<Borrow> allBorrows, List<Borrow> myBorrows,
                                                        List<Book> allBooks, List<Category> categories) {
        List<Borrow> trendBorrows = type == 3 ? allBorrows : myBorrows;
        Map<Long, String> cateMap = categories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
        Map<Long, Book> bookMap = allBooks.stream().collect(Collectors.toMap(Book::getId, b -> b));
        Map<Long, Long> rootCount = new HashMap<>();
        for (Borrow b : trendBorrows) {
            Book book = bookMap.get(b.getBookId());
            if (book != null) {
                Long rootId = findRootCategory(categories, book.getCategoryId());
                rootCount.merge(rootId, 1L, Long::sum);
            }
        }
        return rootCount.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", cateMap.getOrDefault(e.getKey(), "其他"));
                    m.put("value", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private Long findRootCategory(List<Category> all, Long categoryId) {
        Category current = all.stream().filter(c -> c.getId().equals(categoryId)).findFirst().orElse(null);
        if (current == null) return categoryId;
        while (current.getParentId() != null && current.getParentId() != 0L) {
            Long pid = current.getParentId();
            Category parent = all.stream().filter(c -> c.getId().equals(pid)).findFirst().orElse(null);
            if (parent == null) break;
            current = parent;
        }
        return current.getId();
    }
}
