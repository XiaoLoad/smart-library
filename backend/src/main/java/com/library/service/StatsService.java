package com.library.service;

import com.library.entity.Book;
import com.library.entity.Borrow;
import com.library.entity.Category;
import com.library.entity.Fine;
import com.library.entity.User;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowMapper;
import com.library.mapper.CategoryMapper;
import com.library.mapper.FineMapper;
import com.library.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计服务：仪表盘首页与 AI 管理员工具共用同一数据口径。
 * <p>
 * 口径约定（修复排行榜等统计失真问题）：
 * <ul>
 *   <li>「真实借阅」= borrowTime 不为空，即读者实际到馆取过书的记录
 *       （状态 1/2/3/5）；待取书(0)与已取消(4)从未发生借阅，
 *       不计入排行榜、借阅分类分布、借出趋势、累计借阅。</li>
 *   <li>「馆外流通」= 状态 1 借阅中 + 3 已逾期 + 5 待确认归还，
 *       这三种状态图书均未回库。</li>
 *   <li>借出趋势按 borrowTime（实际取书时间）统计，而非申请时间。</li>
 * </ul>
 * 返回结构：
 * <ul>
 *   <li>stats: 卡片数组 [{ key, label, value, unit, icon, trend, danger? }]</li>
 *   <li>operations: 管理员运营数据（待办/今日借出/近30天新书/未缴罚款/活跃读者/在办状态分布）</li>
 *   <li>borrowTrend: { months: [], borrow: [], return: [] }（近 6 个月）</li>
 *   <li>hotBooks: [{ rank, title, author, count }]（真实借阅次数 Top8）</li>
 *   <li>categoryDist: [{ name, value }]（真实借阅次数按一级分类分布）</li>
 *   <li>categoryBookDist: [{ name, titles, copies, availableCopies }]（藏书分类分布）</li>
 *   <li>collection: { titles, copies, availableCopies }（馆藏总量）</li>
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
    @Autowired
    private FineMapper fineMapper;

    /** 是否为真实借阅记录（读者实际取过书：borrowTime 不为空） */
    private boolean isActuallyBorrowed(Borrow b) {
        return b.getBorrowTime() != null;
    }

    /** 是否在馆外（未回库）：借阅中 / 已逾期 / 待确认归还 */
    private boolean isOutsideLibrary(Integer status) {
        return status != null && (status == 1 || status == 3 || status == 5);
    }

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
        List<Fine> allFines = fineMapper.selectList(null, true);

        // 按角色过滤借阅记录
        List<Borrow> myBorrows = type == 3 ? allBorrows : allBorrows.stream()
                .filter(b -> b.getUserId() != null && b.getUserId().equals(currentUserId))
                .collect(Collectors.toList());
        List<Fine> myFines = type == 3 ? allFines : allFines.stream()
                .filter(f -> f.getUserId() != null && f.getUserId().equals(currentUserId))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("stats", buildStats(type, allBooks, allUsers, allBorrows, myBorrows, allFines, myFines));
        data.put("borrowTrend", buildBorrowTrend(type, allBorrows, myBorrows));
        data.put("hotBooks", buildHotBooks(allBorrows, allBooks));
        // 注意：categoryDist 是「真实借阅次数」分布（基于借阅记录），不是藏书量
        data.put("categoryDist", buildCategoryDist(type, allBorrows, myBorrows, allBooks, categories));
        // 馆藏口径数据：分类藏书分布（基于图书）+ 馆藏总量汇总
        data.put("categoryBookDist", buildCategoryBookDist(allBooks, categories));
        data.put("collection", buildCollectionSummary(allBooks));
        // 管理员运营数据（待办/今日/罚款/活跃读者/在办状态分布）
        data.put("operations", buildOperations(type, allBooks, allBorrows, allFines, myBorrows, myFines));
        return data;
    }

    /**
     * 运营数据块。管理员为全馆口径，读者为个人口径（个人只返回部分字段）。
     */
    private Map<String, Object> buildOperations(int type, List<Book> allBooks, List<Borrow> allBorrows,
                                                List<Fine> allFines, List<Borrow> myBorrows, List<Fine> myFines) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthAgo = now.minusDays(30);

        if (type == 3) {
            // 管理员：全馆待办与运营
            m.put("pendingPickup", allBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 0).count());
            m.put("pendingReturnConfirm", allBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 5).count());
            m.put("overdue", allBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 3).count());
            m.put("outsideCount", allBorrows.stream().filter(b -> isOutsideLibrary(b.getStatus())).count());
            m.put("todayBorrow", allBorrows.stream()
                    .filter(b -> b.getBorrowTime() != null && !b.getBorrowTime().isBefore(todayStart)).count());
            m.put("monthNewBooks", allBooks.stream()
                    .filter(b -> b.getCreateTime() != null && b.getCreateTime().isAfter(monthAgo)).count());
            m.put("unpaidFineAmount", sumUnpaidFine(allFines));
            m.put("activeReaders30d", allBorrows.stream()
                    .filter(b -> b.getBorrowTime() != null && b.getBorrowTime().isAfter(monthAgo))
                    .map(Borrow::getUserId).filter(Objects::nonNull).distinct().count());
            // 在办状态分布（环图用）：只展示未完结的活跃状态
            m.put("activeStatusDist", List.of(
                    statusSlice("待取书", allBorrows, 0),
                    statusSlice("借阅中", allBorrows, 1),
                    statusSlice("已逾期", allBorrows, 3),
                    statusSlice("待确认归还", allBorrows, 5)));
        } else {
            // 读者：个人待办
            m.put("pendingPickup", myBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 0).count());
            m.put("pendingReturnConfirm", myBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 5).count());
            m.put("overdue", myBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 3).count());
            m.put("outsideCount", myBorrows.stream().filter(b -> isOutsideLibrary(b.getStatus())).count());
            m.put("unpaidFineAmount", sumUnpaidFine(myFines));
        }
        return m;
    }

    private Map<String, Object> statusSlice(String name, List<Borrow> borrows, int status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", borrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == status).count());
        return m;
    }

    private String sumUnpaidFine(List<Fine> fines) {
        BigDecimal sum = fines.stream()
                .filter(f -> f.getStatus() != null && f.getStatus() == 0 && f.getAmount() != null)
                .map(Fine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.toPlainString();
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
                                                  List<Borrow> allBorrows, List<Borrow> myBorrows,
                                                  List<Fine> allFines, List<Fine> myFines) {
        List<Map<String, Object>> stats = new ArrayList<>();
        if (type == 3) {
            // 管理员：全局统计
            long totalCopies = allBooks.stream()
                    .mapToLong(b -> b.getTotalCount() == null ? 0 : b.getTotalCount()).sum();
            long outside = allBorrows.stream().filter(b -> isOutsideLibrary(b.getStatus())).count();
            long overdue = allBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 3).count();
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            long todayBorrow = allBorrows.stream()
                    .filter(b -> b.getBorrowTime() != null && !b.getBorrowTime().isBefore(todayStart)).count();
            stats.add(statItem("books", "馆藏图书", totalCopies, "册", "Reading", "共 " + allBooks.size() + " 种"));
            stats.add(statItem("users", "在册读者", allUsers.stream().filter(u -> u.getStatus() == 1).count(), "人", "User",
                    allUsers.size() + " 人注册"));
            stats.add(statItem("outside", "馆外流通", outside, "本", "Tickets", "借阅中·逾期·待确认"));
            stats.add(statItem("overdue", "逾期未还", overdue, "本", "Warning",
                    overdue > 0 ? "需要催还" : "无逾期", overdue > 0));
            stats.add(statItem("todayBorrow", "今日借出", todayBorrow, "本", "DataAnalysis",
                    todayBorrow > 0 ? "柜台实时" : "今日暂无借出"));
            stats.add(statItem("fine", "未缴罚款", new java.math.BigDecimal(sumUnpaidFine(allFines)), "元", "Coin",
                    "全馆待缴合计"));
        } else {
            // 教师/学生：个人统计
            long outside = myBorrows.stream().filter(b -> isOutsideLibrary(b.getStatus())).count();
            long overdue = myBorrows.stream().filter(b -> b.getStatus() != null && b.getStatus() == 3).count();
            long borrowed = myBorrows.stream().filter(this::isActuallyBorrowed).count();
            long dueSoon = myBorrows.stream().filter(b -> {
                if (b.getStatus() != null && b.getStatus() == 1 && b.getDueTime() != null) {
                    return b.getDueTime().isBefore(LocalDateTime.now().plusDays(7));
                }
                return false;
            }).count();
            String myFine = sumUnpaidFine(myFines);
            boolean hasFine = new BigDecimal(myFine).compareTo(BigDecimal.ZERO) > 0;
            stats.add(statItem("outside", "我借的书", outside, "本", "Tickets",
                    dueSoon > 0 ? dueSoon + " 本即将到期" : (overdue > 0 ? overdue + " 本已逾期" : "无即将到期")));
            stats.add(statItem("history", "累计借阅", borrowed, "本", "Reading", "实际取书次数"));
            stats.add(statItem("dueSoon", "即将到期", dueSoon, "本", "Warning",
                    dueSoon > 0 ? "7 天内到期" : "无", dueSoon > 0));
            stats.add(statItem("fine", "待缴罚款", new BigDecimal(myFine), "元", "Coin",
                    hasFine ? "请及时缴纳" : "无欠款", hasFine));
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
     * 构建借阅趋势（近 6 个月）。
     * 借出按 borrowTime（实际取书时间，仅真实借阅），归还按 returnTime。
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
                    .filter(b -> b.getBorrowTime() != null
                            && b.getBorrowTime().isAfter(start) && b.getBorrowTime().isBefore(end))
                    .count());
            returnList.add(trendBorrows.stream()
                    .filter(b -> b.getReturnTime() != null
                            && b.getReturnTime().isAfter(start) && b.getReturnTime().isBefore(end))
                    .count());
        }
        Map<String, Object> trend = new HashMap<>();
        trend.put("months", months);
        trend.put("borrow", borrow);
        trend.put("return", returnList);
        return trend;
    }

    /**
     * 构建热门图书（真实借阅次数 Top8）。
     * 仅统计实际取过书的记录（borrowTime 不为空），排除待取书/已取消。
     */
    private List<Map<String, Object>> buildHotBooks(List<Borrow> allBorrows, List<Book> allBooks) {
        List<Borrow> trendBorrows = allBorrows.stream()
                .filter(this::isActuallyBorrowed)
                .collect(Collectors.toList());
        Map<Long, Long> bookCount = new HashMap<>();
        Map<Long, Book> bookMap = allBooks.stream().collect(Collectors.toMap(Book::getId, b -> b));
        for (Borrow b : trendBorrows) {
            bookCount.merge(b.getBookId(), 1L, Long::sum);
        }
        List<Map.Entry<Long, Long>> sorted = bookCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(8)
                .collect(Collectors.toList());
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<Long, Long> e : sorted) {
            Book book = bookMap.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("bookId", e.getKey());
            m.put("title", book != null ? book.getTitle() : "未知");
            m.put("author", book != null ? book.getAuthor() : "");
            m.put("coverUrl", book != null ? book.getCoverUrl() : null);
            m.put("availableCount", book != null ? book.getAvailableCount() : 0);
            m.put("totalCount", book != null ? book.getTotalCount() : 0);
            m.put("count", e.getValue());
            result.add(m);
        }
        return result;
    }

    /**
     * 构建分类分布（按一级分类汇总真实借阅次数）。
     */
    private List<Map<String, Object>> buildCategoryDist(int type, List<Borrow> allBorrows, List<Borrow> myBorrows,
                                                        List<Book> allBooks, List<Category> categories) {
        List<Borrow> trendBorrows = (type == 3 ? allBorrows : myBorrows).stream()
                .filter(this::isActuallyBorrowed)
                .collect(Collectors.toList());
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
                .sorted((x, y) -> Long.compare((Long) y.get("value"), (Long) x.get("value")))
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
