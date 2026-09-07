package com.library.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 前端使用的用户视图对象
 */
@Data
@Builder
@AllArgsConstructor
public class UserVo {
    private Long id;
    private String username;
    private String realName;
    private Integer userType;
    private String roleName;
    private String avatar;
}
