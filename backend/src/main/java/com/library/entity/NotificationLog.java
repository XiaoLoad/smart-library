package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationLog {
    private Long id;
    private String borrowNo;
    private Long userId;
    private String eventType;
    private String channel;
    private String status;
    private String toEmail;
    private String subject;
    private String content;
    private String errorMsg;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private Long ruleId;
}
