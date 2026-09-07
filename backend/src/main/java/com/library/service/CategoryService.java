package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.dto.CategoryUpdateRequest;
import com.library.entity.Category;
import com.library.mapper.CategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类服务
 */
@Service
public class CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 获取分类树
     */
    public List<Category> categoryTree() {
        List<Category> all = categoryMapper.selectAll();
        return buildTree(all, 0L);
    }

    private List<Category> buildTree(List<Category> all, Long parentId) {
        List<Category> tree = new ArrayList<>();
        for (Category c : all) {
            if (c.getParentId().equals(parentId)) {
                c.setChildren(buildTree(all, c.getId()));
                tree.add(c);
            }
        }
        return tree;
    }

    /**
     * 新增分类
     */
    public Category add(String categoryName, String categoryCode, Long parentId, Integer sortOrder) {
        if (categoryMapper.selectByCode(categoryCode) != null) {
            throw new BusinessException("分类编码已存在");
        }
        Category category = new Category();
        category.setCategoryName(categoryName);
        category.setCategoryCode(categoryCode);
        category.setParentId(parentId == null ? 0L : parentId);
        category.setSortOrder(sortOrder == null ? 1 : sortOrder);
        category.setStatus(1);
        // 计算层级
        if (parentId == null || parentId == 0L) {
            category.setLevel(1);
        } else {
            Category parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException("父分类不存在");
            }
            category.setLevel(parent.getLevel() + 1);
        }
        categoryMapper.insert(category);
        return category;
    }

    /**
     * 编辑分类
     */
    public Category update(CategoryUpdateRequest req) {
        Category category = categoryMapper.selectById(req.getId());
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        category.setCategoryName(req.getCategoryName());
        if (req.getSortOrder() != null) {
            category.setSortOrder(req.getSortOrder());
        }
        if (req.getStatus() != null) {
            category.setStatus(req.getStatus());
        }
        categoryMapper.update(category);
        return category;
    }

    /**
     * 递归收集子孙分类 id（含自身）
     */
    public List<Long> collectDescendantIds(Long rootId) {
        List<Category> all = categoryMapper.selectAll();
        List<Long> result = new ArrayList<>();
        result.add(rootId);
        collectChildren(all, rootId, result);
        return result;
    }

    private void collectChildren(List<Category> all, Long parentId, List<Long> out) {
        for (Category c : all) {
            if (c.getParentId().equals(parentId)) {
                out.add(c.getId());
                collectChildren(all, c.getId(), out);
            }
        }
    }
}
