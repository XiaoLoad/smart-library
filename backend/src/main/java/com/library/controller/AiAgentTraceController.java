package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.entity.AiAgentTrace;
import com.library.security.CurrentUser;
import com.library.service.AiAgentTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M6a：Agent 运行轨迹（管理员可见）。
 * <p>
 * 展示 Agent 每次对话的工具序列、决策轮次、授权门禁触发情况与 token 消耗，
 * 既是运维排障入口，也是技术文档里的可量化运行数据来源。
 */
@RestController
@RequestMapping("/ai/trace")
public class AiAgentTraceController {

    @Autowired
    private AiAgentTraceService traceService;

    /** 仅管理员可访问（运行轨迹含提问内容与读者身份，属敏感运营数据） */
    private void requireAdmin() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserType() == null || cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可查看 Agent 运行日志");
        }
    }

    /**
     * 轨迹列表（分页 + 状态/关键词筛选）
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword) {
        requireAdmin();
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * limit;
        List<AiAgentTrace> list = traceService.page(offset, limit, status, keyword);
        int total = traceService.count(status, keyword);
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", total);
        return Result.success(data);
    }

    /**
     * 轨迹详情（含完整工具序列与决策旁白）
     */
    @GetMapping("/{id}")
    public Result<AiAgentTrace> detail(@PathVariable Long id) {
        requireAdmin();
        return Result.success(traceService.get(id));
    }

    /**
     * 汇总统计：总轮次、总工具调用、门禁触发数、降级数、token 合计、平均耗时
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        requireAdmin();
        return Result.success(traceService.summary());
    }
}
