package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationRule {
    private Long id;
    private String eventType;
    private String channel;
    private Integer enabled;
    private Integer offsetDays;
    private String subjectTpl;
    private String bodyTpl;
    private LocalDateTime updatedAt;
}
