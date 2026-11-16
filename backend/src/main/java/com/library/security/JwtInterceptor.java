package com.library.security;

import com.library.entity.User;
import com.library.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 拦截器
 * <p>
 * 除验签外，每次请求回查 users 表校验账号实时状态：
 * <ul>
 *   <li>账号被禁用（status != 1）→ 立即拒绝，旧 token 即刻失效；</li>
 *   <li>账号被删除 → 拒绝；</li>
 *   <li>角色（userType）以库中当前值为准——管理员被降权后旧 token 里的
 *       userType=3 不再生效，权限口径始终与 DB 一致。</li>
 * </ul>
 * 代价是每个请求多一次主键查询（毫秒级），换来「禁用/降权/删除即撤销」的能力。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserMapper userMapper;

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
            // 实时校验账号状态与角色：禁用/删除/降权后旧 token 立即失效
            User user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                writeUnauthorized(resp, "账号不存在或已被禁用");
                return false;
            }
            CurrentUser.set(user.getId(), user.getUsername(), user.getUserType());
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
