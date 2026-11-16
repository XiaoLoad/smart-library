package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ASR 渠道排序请求：direction = -1 上移（提高优先级），1 下移（降低优先级）。
 */
@Data
public class AsrChannelMoveRequest {
    @NotNull(message = "渠道 ID 不能为空")
    private Long id;
    @NotNull(message = "移动方向不能为空")
    private Integer direction;
}
