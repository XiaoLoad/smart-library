package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 渠道排序请求：direction=-1 上移（优先级升高），1 下移（优先级降低）
 */
@Data
public class AiChannelMoveRequest {

    @NotNull(message = "渠道 ID 不能为空")
    private Long id;

    @NotNull(message = "移动方向不能为空")
    private Integer direction;
}
