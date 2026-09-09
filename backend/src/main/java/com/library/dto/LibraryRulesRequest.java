package com.library.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 馆内规则保存请求（逾期罚款费率等）
 */
@Data
public class LibraryRulesRequest {

    /** 逾期罚款费率（元/天，按自然日计算）；0 表示不收取滞纳金 */
    @NotNull(message = "罚款费率不能为空")
    @DecimalMin(value = "0.00", message = "罚款费率不能为负数")
    @DecimalMax(value = "10.00", message = "罚款费率不能超过 10.00 元/天")
    private BigDecimal fineRatePerDay;
}
