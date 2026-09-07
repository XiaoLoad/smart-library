package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Notification {
    private Long id;
    private Long userId;
    private Integer type;
    private String title;
    private String content;
    private Integer isRead;
    private LocalDateTime createTime;
}
