package com.library.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private String realName;
    private Integer userType;
    private String roleName;
    private String email;
    private String phone;
    private Integer status;
    private LocalDateTime createTime;
    private String avatar;
    private String teacherNo;
    private String department;
    private String title;
    private String studentNo;
    private String className;
    private String grade;
    private String major;
}
