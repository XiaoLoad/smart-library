package com.library.controller;

import com.library.common.Result;
import com.library.security.CurrentUser;
import com.library.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仪表盘控制器
 * 统计逻辑统一由 {@link StatsService} 提供（与 AI 管理员工具同口径）：
 * - stats: 数组 [{ key, label, value, unit, icon, trend, danger? }]
 * - borrowTrend: { months: [], borrow: [], return: [] }
 * - hotBooks: [{ rank, title, author, count }]
 * - categoryDist: [{ name, value }]
 * <p>
 * 统计口径（管理员=全馆 / 读者=个人）只由 token 里的身份决定，
 * 不接受任何外部参数——否则读者可通过传 userType=3 读到全馆运营统计。
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Autowired
    private StatsService statsService;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        CurrentUser cu = CurrentUser.get();
        return Result.success(statsService.overview(cu.getUserType(), cu.getUserId()));
    }
}
