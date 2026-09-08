package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 借阅记录
 */
@Data
public class Borrow {
    private Long id;
    private String borrowNo;
    private Long userId;
    private String userName;
    private Long bookId;
    private String bookTitle;
    private LocalDateTime applyTime;
    private LocalDateTime borrowTime;
    private LocalDateTime dueTime;
    private LocalDateTime returnTime;
    private Integer renewCount;
    private Integer renewMaxCount;
    private LocalDateTime pickupDeadline;
    private LocalDateTime returnRequestTime;
    private Long pickupAdminId;
    private Long confirmAdminId;
    private Integer status;
    private String remark;
    private String qrPayload;
}
