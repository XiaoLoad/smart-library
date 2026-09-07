package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.LoginRequest;
import com.library.dto.LoginResponse;
import com.library.dto.UserVo;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtUtil;
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

    @PostMapping("/auth/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        User user = userMapper.selectByUsername(req.getUsername());
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
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
