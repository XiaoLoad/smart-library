package com.library.entity;

import lombok.Data;
import java.util.List;

/**
 * 分类实体（含子节点）
 */
@Data
public class Category {
    private Long id;
    private Long parentId;
    private String categoryName;
    private String categoryCode;
    private Integer level;
    private Integer sortOrder;
    private Integer status;
    private List<Category> children;
}
