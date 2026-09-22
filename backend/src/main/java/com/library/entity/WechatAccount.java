package com.library.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信小程序账号绑定（一个微信绑定一个系统用户）
 */
@Data
public class WechatAccount {
    private Long id;
    private Long userId;
    private String openid;
    private String unionid;
    private LocalDateTime createTime;
}
