package com.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 读者自助修改个人信息的请求。
 * <p>
 * 只声明本人可维护的字段——联系方式。姓名、身份、学号/工号、状态，以及院系/职称/班级/
 * 年级/专业等档案信息均由图书馆维护，不在此声明：请求体里多带的这些字段在反序列化阶段就被丢弃，
 * 本人无从改动。
 */
@Data
public class UserProfileUpdateRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 80, message = "邮箱长度不能超过 80 个字符")
    private String email;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号须为 11 位")
    private String phone;
}
