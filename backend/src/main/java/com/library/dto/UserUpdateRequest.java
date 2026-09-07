package com.library.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserUpdateRequest {
    @NotNull(message = "ID不能为空")
    private Long id;
    private String realName;
    private Integer userType;
    private String email;
    private String phone;
    private String teacherNo;
    private String department;
    private String title;
    private String studentNo;
    private String className;
    private String grade;
    private String major;
    private Integer status;
}
