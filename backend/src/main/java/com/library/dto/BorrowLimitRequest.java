package com.library.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 借阅额度规则保存请求（按读者身份：1=教师 2=学生）
 */
@Data
public class BorrowLimitRequest {

    /** 读者身份：1=教师，2=学生（管理员不可借阅，无额度规则） */
    @NotNull(message = "读者身份不能为空")
    @Min(value = 1, message = "非法的读者身份")
    @Max(value = 2, message = "非法的读者身份")
    private Integer userType;

    /** 最大同时借阅册数 */
    @NotNull(message = "最大借阅册数不能为空")
    @Min(value = 1, message = "最大借阅册数至少为 1")
    @Max(value = 99, message = "最大借阅册数不能超过 99")
    private Integer maxBooks;

    /** 单次借阅天数 */
    @NotNull(message = "借阅天数不能为空")
    @Min(value = 1, message = "借阅天数至少为 1 天")
    @Max(value = 365, message = "借阅天数不能超过 365 天")
    private Integer loanDays;

    /** 最大续借次数 */
    @NotNull(message = "续借次数不能为空")
    @Min(value = 0, message = "续借次数不能为负")
    @Max(value = 10, message = "续借次数不能超过 10 次")
    private Integer renewMaxCount;

    /** 每次续借延长天数 */
    @NotNull(message = "续借天数不能为空")
    @Min(value = 1, message = "续借天数至少为 1 天")
    @Max(value = 365, message = "续借天数不能超过 365 天")
    private Integer renewDays;

    /** 申请后取书期限（天），超期未取自动取消 */
    @NotNull(message = "取书期限不能为空")
    @Min(value = 1, message = "取书期限至少为 1 天")
    @Max(value = 60, message = "取书期限不能超过 60 天")
    private Integer pickupDays;
}
