package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiConversation {
    private Long id;
    private Long userId;
    private String title;
    private String model;
    private Integer status;
    /** 写操作权限档位：confirm=逐笔确认 / batch=批量授权 / auto=自动执行（仅管理员） */
    private String permissionMode;
    private LocalDateTime createTime;
}
