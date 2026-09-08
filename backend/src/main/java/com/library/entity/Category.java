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

    /** 该分类（含全部子孙分类）下的图书数量，仅用于树形展示，不映射数据库列 */
    private Integer bookCount;
}
