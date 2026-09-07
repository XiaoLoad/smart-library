package com.library.controller;

import com.library.common.Result;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 仪表盘控制器
 * 返回格式与前端 mock 完全对齐：
 * - stats: 数组 [{ key, label, value, unit, icon, trend, danger? }]
 * - borrowTrend: { months: [], borrow: [], return: [] }
 * - hotBooks: [{ rank, title, author, count }]
 * - categoryDist: [{ name, value }]
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private BorrowMapper borrowMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Integer userType) {
        CurrentUser cu = CurrentUser.get();
        int type = userType != null ? userType : cu.getUserType();

        List<Book> allBooks = bookMapper.selectList(null, null);
        List<Borrow> allBorrows = borrowMapper.selectList(null, true, null, null);
        List<User> allUsers = userMapper.selectAll();
        List<Category> categories = categoryMapper.selectAll();

        // 按角色过滤借阅记录
        List<Borrow> myBorrows = type == 3 ? allBorrows : allBorrows.stream()
                .filter(b -> b.getUserId().equals(cu.getUserId()))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("stats", buildStats(type, allBooks, allUsers, allBorrows, myBorrows));
        data.put("borrowTrend", buildBorrowTrend(type, allBorrows, myBorrows));
        data.put("hotBooks", buildHotBooks(type, allBorrows, myBorrows, allBooks));
        data.put("categoryDist", buildCategoryDist(type, allBorrows, myBorrows, allBooks, categories));

        return Result.success(data);
    }

    /**
     * 构建统计卡片数组
     */
    private List<Map<String, Object>> buildStats(int type, List<Book> allBooks, List<User> allUsers,
                                                  List<Borrow> allBorrows, List<Borrow> myBorrows) {
        List<Map<String, Object>> stats = new ArrayList<>();
        if (type == 3) {
            // 管理员：全局统计
            stats.add(statItem("books", "馆藏图书", allBooks.size(), "册", "Reading", "+" + allBooks.size() + " 总计"));
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
     * 构建借阅趋势
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
     * 构建热门图书
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
     * 构建分类分布
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
