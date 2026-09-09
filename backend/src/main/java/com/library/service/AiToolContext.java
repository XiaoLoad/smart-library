package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.mapper.AnnouncementMapper;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowLimitMapper;
import com.library.mapper.BorrowMapper;
import com.library.mapper.CategoryMapper;
import com.library.mapper.FineMapper;
import com.library.mapper.NotificationMapper;
import com.library.mapper.UserMapper;

/**
 * AI 工具依赖上下文：每次对话由 {@link AiService} 组装一次，
 * 注入 {@link CommonTools} / {@link ReaderTools} / {@link AdminTools}。
 * <p>
 * 工具实例本身持有会话用户身份（userId/userType），上下文只持有无状态组件。
 */
public class AiToolContext {

    public final BorrowMapper borrowMapper;
    public final BookMapper bookMapper;
    public final FineMapper fineMapper;
    public final AnnouncementMapper announcementMapper;
    public final BorrowLimitMapper borrowLimitMapper;
    public final UserMapper userMapper;
    public final CategoryMapper categoryMapper;
    public final NotificationMapper notificationMapper;
    public final CategoryService categoryService;
    public final BorrowService borrowService;
    public final StatsService statsService;
    public final ObjectMapper objectMapper;

    public AiToolContext(BorrowMapper borrowMapper,
                         BookMapper bookMapper,
                         FineMapper fineMapper,
                         AnnouncementMapper announcementMapper,
                         BorrowLimitMapper borrowLimitMapper,
                         UserMapper userMapper,
                         CategoryMapper categoryMapper,
                         NotificationMapper notificationMapper,
                         CategoryService categoryService,
                         BorrowService borrowService,
                         StatsService statsService,
                         ObjectMapper objectMapper) {
        this.borrowMapper = borrowMapper;
        this.bookMapper = bookMapper;
        this.fineMapper = fineMapper;
        this.announcementMapper = announcementMapper;
        this.borrowLimitMapper = borrowLimitMapper;
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
        this.notificationMapper = notificationMapper;
        this.categoryService = categoryService;
        this.borrowService = borrowService;
        this.statsService = statsService;
        this.objectMapper = objectMapper;
    }
}
