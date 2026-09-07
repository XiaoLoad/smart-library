package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类编辑请求
 */
@Data
public class CategoryUpdateRequest {
    @NotNull(message = "ID不能为空")
    private Long id;
    @NotBlank(message = "分类名称不能为空")
    private String categoryName;
    private Integer sortOrder;
    private Integer status;
}
