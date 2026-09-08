package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类删除请求
 */
@Data
public class CategoryDeleteRequest {

    @NotNull(message = "分类 ID 不能为空")
    private Long id;
}
