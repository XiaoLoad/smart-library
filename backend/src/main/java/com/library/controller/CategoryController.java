package com.library.controller;

import com.library.common.Result;
import com.library.dto.CategoryAddRequest;
import com.library.dto.CategoryDeleteRequest;
import com.library.dto.CategoryStatusRequest;
import com.library.dto.CategoryUpdateRequest;
import com.library.entity.Category;
import com.library.security.CurrentUser;
import com.library.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类控制器
 */
@RestController
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @PostMapping("/category/add")
    public Result<Category> add(@Valid @RequestBody CategoryAddRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可新增分类");
        }
        Category category = categoryService.add(
                req.getCategoryName(),
                req.getCategoryCode(),
                req.getParentId(),
                req.getSortOrder()
        );
        return Result.success("新增成功", category);
    }

    @PostMapping("/category/update")
    public Result<Category> update(@Valid @RequestBody CategoryUpdateRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可编辑分类");
        }
        Category category = categoryService.update(req);
        return Result.success("更新成功", category);
    }

    @PostMapping("/category/status")
    public Result<Void> changeStatus(@Valid @RequestBody CategoryStatusRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可修改分类状态");
        }
        int affected = categoryService.changeStatus(req.getId(), req.getStatus());
        String message = req.getStatus() == 1
                ? "已启用"
                : (affected > 1 ? "已禁用（含 " + (affected - 1) + " 个子分类）" : "已禁用");
        return Result.success(message, null);
    }

    @PostMapping("/category/delete")
    public Result<Void> delete(@Valid @RequestBody CategoryDeleteRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可删除分类");
        }
        int affected = categoryService.delete(req.getId());
        String message = affected > 1
                ? "已删除（含 " + (affected - 1) + " 个子分类）"
                : "已删除";
        return Result.success(message, null);
    }
}
