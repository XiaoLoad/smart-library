package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserAddRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;
    @NotBlank(message = "姓名不能为空")
    private String realName;
    @NotNull(message = "类型不能为空")
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
