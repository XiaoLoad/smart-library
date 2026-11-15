package com.library.controller;

import com.library.common.Result;
import com.library.security.CurrentUser;
import com.library.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图书推荐控制器
 * - GET /recommend: 个性化推荐（三层混合：借阅共现 ItemCF + 向量语义相似 + 画像热门兜底）
 * - GET /book/{id}/similar: 相似图书（详情页「读过此书的人还读」）
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    @Autowired
    private RecommendService recommendService;

    /** 个性化推荐（登录用户，默认 8 本） */
    @GetMapping("/personal")
    public Result<List<Map<String, Object>>> personal(@RequestParam(defaultValue = "8") int limit) {
        CurrentUser cu = CurrentUser.get();
        return Result.success(recommendService.recommendForUser(cu.getUserId(), Math.min(Math.max(limit, 1), 20)));
    }

    /** 相似图书（登录用户，默认 6 本） */
    @GetMapping("/similar/{bookId}")
    public Result<List<Map<String, Object>>> similar(@PathVariable Long bookId,
                                                     @RequestParam(defaultValue = "6") int limit) {
        return Result.success(recommendService.similarBooks(bookId, Math.min(Math.max(limit, 1), 20)));
    }
}
