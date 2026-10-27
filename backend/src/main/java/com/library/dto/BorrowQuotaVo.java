package com.library.dto;

import lombok.Data;

/**
 * 借阅额度
 */
@Data
public class BorrowQuotaVo {
    private Integer maxBooks;
    private Integer used;
    private Integer remaining;
    /** 逾期未还总数（overdueHolding + pendingReturnOverdue），保持与旧字段语义兼容 */
    private Integer overdueCount;
    /** 在手逾期（status=3，书未归还） */
    private Integer overdueHolding;
    /** 逾期归还待确认（status=5 且申请归还晚于应还日，罚款未结算） */
    private Integer pendingReturnOverdue;
    private Boolean canBorrow;
}
