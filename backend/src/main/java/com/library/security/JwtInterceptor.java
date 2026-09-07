package com.library.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 拦截器
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        // 跳过 OPTIONS 预检
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(resp, "未登录或 token 缺失");
            return false;
        }
        try {
            String token = auth.substring(7).trim();
            Claims claims = jwtUtil.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            Integer userType = claims.get("userType", Integer.class);
            CurrentUser.set(userId, username, userType);
            return true;
        } catch (Exception e) {
            writeUnauthorized(resp, "token 无效或已过期");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private void writeUnauthorized(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(200);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":401,\"message\":\"" + msg + "\",\"data\":null}");
    }
}
