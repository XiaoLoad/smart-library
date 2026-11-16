package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ASR 渠道启用/停用请求。
 */
@Data
public class AsrChannelToggleRequest {
    @NotNull(message = "渠道 ID 不能为空")
    private Long id;
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
