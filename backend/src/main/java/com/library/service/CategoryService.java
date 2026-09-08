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
            if (parent.getStatus() == null || parent.getStatus() == 0) {
                throw new BusinessException("上级分类「" + parent.getCategoryName() + "」已禁用，无法在其下新增子分类");
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

    /**
     * 切换分类启用状态
     * 禁用：级联禁用自身及全部子孙分类（避免"父禁用子启用"的矛盾状态）
     * 启用：要求父链全部启用，仅启用自身（子分类的禁用状态保持独立）
     *
     * @return 本次受影响的分类数量
     */
    public int changeStatus(Long id, Integer status) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        int current = category.getStatus() == null ? 1 : category.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("状态参数不合法");
        }
        if (status == current) {
            throw new BusinessException(status == 1 ? "分类已是启用状态" : "分类已是禁用状态");
        }
        if (status == 0) {
            List<Long> ids = collectDescendantIds(id);
            categoryMapper.updateStatusBatch(ids, 0);
            return ids.size();
        }
        // 启用：沿父链向上校验
        String disabledParent = findDisabledAncestor(category.getParentId());
        if (disabledParent != null) {
            throw new BusinessException("请先启用上级分类「" + disabledParent + "」");
        }
        category.setStatus(1);
        categoryMapper.update(category);
        return 1;
    }

    /**
     * 向上查找最近一个被禁用的祖先分类，无则返回 null
     */
    private String findDisabledAncestor(Long parentId) {
        Long cur = parentId;
        while (cur != null && cur > 0) {
            Category parent = categoryMapper.selectById(cur);
            if (parent == null) {
                break;
            }
            if (parent.getStatus() == null || parent.getStatus() == 0) {
                return parent.getCategoryName();
            }
            cur = parent.getParentId();
        }
        return null;
    }

    /**
     * 分类是否可用（自身及全部祖先均启用），图书新增/编辑时校验
     */
    public void assertSelectable(Long categoryId) {
        if (categoryId == null) {
            throw new BusinessException("请选择图书分类");
        }
        Category self = categoryMapper.selectById(categoryId);
        if (self == null) {
            throw new BusinessException("所选分类不存在");
        }
        Long cur = categoryId;
        while (cur != null && cur > 0) {
            Category c = categoryMapper.selectById(cur);
            if (c == null) {
                throw new BusinessException("所选分类不存在");
            }
            if (c.getStatus() == null || c.getStatus() == 0) {
                throw new BusinessException("分类「" + c.getCategoryName() + "」已禁用，请重新选择");
            }
            cur = c.getParentId();
        }
    }
}
