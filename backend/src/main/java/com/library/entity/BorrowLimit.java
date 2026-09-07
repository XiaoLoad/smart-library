package com.library.entity;

import lombok.Data;

/**
 * 借阅额度规则
 */
@Data
public class BorrowLimit {
    private Long id;
    private Integer userType;
    private Integer maxBooks;
    private Integer loanDays;
    private Integer renewMaxCount;
    private Integer renewDays;
    private Integer pickupDays;
}
