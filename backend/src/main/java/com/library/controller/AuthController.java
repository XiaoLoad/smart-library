package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.LoginRequest;
import com.library.dto.LoginResponse;
import com.library.dto.UserVo;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtUtil;
import com.library.security.LoginAttemptGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@RestController
public class AuthController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    /**
     * 登录失败时拒绝计数不区分「用户不存在」与「密码错误」：
     * 同一提示语避免给攻击者提供用户名存在性的探测信号。
     */
    private static final String BAD_CREDENTIALS = "用户名或密码错误";

    @PostMapping("/auth/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        String ip = LoginAttemptGuard.clientIp();
        loginAttemptGuard.check(req.getUsername(), ip);
        User user = userMapper.selectByUsername(req.getUsername());
        if (user == null || user.getStatus() != 1) {
            loginAttemptGuard.onFailure(req.getUsername(), ip);
            throw new BusinessException(401, BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            loginAttemptGuard.onFailure(req.getUsername(), ip);
            throw new BusinessException(401, BAD_CREDENTIALS);
        }
        loginAttemptGuard.onSuccess(req.getUsername(), ip);
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getUserType());
        UserVo vo = UserVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .userType(user.getUserType())
                .roleName(user.getRoleName())
                .avatar(user.getAvatar())
                .build();
        return Result.success("登录成功", new LoginResponse(token, vo));
    }
}
