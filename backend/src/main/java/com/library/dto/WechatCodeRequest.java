package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信登录/绑定请求（wx.login 返回的 code）
 */
@Data
public class WechatCodeRequest {
    @NotBlank(message = "code 不能为空")
    private String code;
}
