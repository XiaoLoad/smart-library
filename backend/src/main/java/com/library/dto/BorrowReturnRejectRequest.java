package com.library.dto;

import lombok.Data;

/**
 * 驳回归还申请请求
 */
@Data
public class BorrowReturnRejectRequest {

    private Long id;

    private String borrowNo;

    /**
     * 驳回原因（如 图书缺失/污损）
     */
    private String reason;
}
