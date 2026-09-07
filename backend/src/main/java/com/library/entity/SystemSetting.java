package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统设置实体
 */
@Data
public class SystemSetting {
    private Long id;
    private String settingKey;
    private String settingValue;
    private LocalDateTime updateTime;
}
