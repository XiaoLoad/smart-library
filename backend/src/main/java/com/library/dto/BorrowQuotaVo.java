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
    private Integer overdueCount;
    private Boolean canBorrow;
}
