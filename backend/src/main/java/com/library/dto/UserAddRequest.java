package com.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserAddRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 100, message = "密码长度不能超过 100 个字符")
    private String password;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名长度不能超过 50 个字符")
    private String realName;

    @NotNull(message = "类型不能为空")
    private Integer userType;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
    private String email;

    // 允许为空（管理员建号时可不填，由读者自助补全）；一旦填写须为 11 位手机号
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号须为 11 位手机号")
    @Size(max = 20, message = "手机号长度不能超过 20 个字符")
    private String phone;

    @Size(max = 30, message = "工号长度不能超过 30 个字符")
    private String teacherNo;

    @Size(max = 60, message = "院系长度不能超过 60 个字符")
    private String department;

    @Size(max = 30, message = "职称长度不能超过 30 个字符")
    private String title;

    @Size(max = 30, message = "学号长度不能超过 30 个字符")
    private String studentNo;

    @Size(max = 30, message = "班级长度不能超过 30 个字符")
    private String className;

    @Size(max = 10, message = "年级长度不能超过 10 个字符")
    private String grade;

    @Size(max = 60, message = "专业长度不能超过 60 个字符")
    private String major;

    private Integer status;
}
