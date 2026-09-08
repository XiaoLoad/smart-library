package com.library.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类状态切换请求
 */
@Data
public class CategoryStatusRequest {

    @NotNull(message = "分类 ID 不能为空")
    private Long id;

    /**
     * 0=禁用 1=启用
     */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态参数不合法")
    @Max(value = 1, message = "状态参数不合法")
    private Integer status;
}
