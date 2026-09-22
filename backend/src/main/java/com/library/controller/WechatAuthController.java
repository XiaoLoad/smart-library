package com.library.controller;

import com.library.common.Result;
import com.library.dto.LoginResponse;
import com.library.dto.WechatCodeRequest;
import com.library.security.CurrentUser;
import com.library.service.WechatAuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信小程序登录 / 绑定（读者端）。
 * /auth/wechat-login 免登录；其余接口需携带 JWT。
 */
@RestController
@RequestMapping("/auth")
public class WechatAuthController {

    @Autowired
    private WechatAuthService wechatAuthService;

    /** 微信一键登录（仅已绑定微信的账号可用） */
    @PostMapping("/wechat-login")
    public Result<LoginResponse> wechatLogin(@Valid @RequestBody WechatCodeRequest req) {
        return Result.success("登录成功", wechatAuthService.login(req.getCode()));
    }

    /** 绑定当前登录用户与当前微信 */
    @PostMapping("/wechat-bind")
    public Result<Void> bind(@Valid @RequestBody WechatCodeRequest req) {
        wechatAuthService.bind(CurrentUser.get().getUserId(), req.getCode());
        return Result.success("绑定成功", null);
    }

    /** 解绑微信 */
    @PostMapping("/wechat-unbind")
    public Result<Void> unbind() {
        wechatAuthService.unbind(CurrentUser.get().getUserId());
        return Result.success("已解绑", null);
    }

    /** 当前用户微信绑定状态 */
    @GetMapping("/wechat-status")
    public Result<Map<String, Object>> status() {
        boolean bound = wechatAuthService.isBound(CurrentUser.get().getUserId());
        return Result.success(Map.of("bound", bound));
    }
}
