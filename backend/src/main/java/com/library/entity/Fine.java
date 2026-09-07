package com.library.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Fine {
    private Long id;
    private Long userId;
    private String userName;
    private Long borrowRecordId;
    private String borrowNo;
    private String bookTitle;
    private BigDecimal amount;
    private Integer daysOverdue;
    private String reason;
    private Integer status;
    private LocalDateTime paidTime;
    private LocalDateTime createTime;
}
