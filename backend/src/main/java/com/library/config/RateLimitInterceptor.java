package com.library.config;

import com.library.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 接口滑动窗口限流拦截器。
 * <p>
 * 对 LLM 相关的高成本接口按用户（未登录时按来源 IP）做每分钟请求数限制，
 * 防止单个用户高频刷接口耗尽 token 配额或拖垮服务。
 * <p>
 * <b>实现</b>：每个 key 维护一个毫秒时间戳双端队列，preHandle 时先剔除窗口外
 * 的过期时间戳再判断窗口内请求数；窗口数量超阈值时顺带清理空闲 key，避免内存无限增长。
 * <p>
 * <b>注册顺序</b>：必须在 {@code JwtInterceptor} 之后注册（见 WebMvcConfig），
 * 才能通过 {@link CurrentUser} 拿到登录用户身份作为限流 key。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 滑动窗口长度（毫秒）：60s 即「每分钟」 */
    private static final long WINDOW_MS = 60_000L;
    /** key 多久没有新请求就清掉其窗口（毫秒），避免长期占用内存 */
    private static final long IDLE_EVICT_MS = 10 * 60_000L;

    /** AI 对话接口每分钟最大请求数 */
    @Value("${app.rate-limit.ai-chat-per-minute:10}")
    private int aiChatPerMinute;

    /** AI 确认执行接口每分钟最大请求数 */
    @Value("${app.rate-limit.ai-confirm-per-minute:20}")
    private int aiConfirmPerMinute;

    /** 限流窗口：key（用户ID或IP + 路径）→ 请求时间戳队列 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        int limit = limitFor(request.getRequestURI());
        if (limit <= 0) {
            return true;
        }
        String key = limitKey(request) + "|" + request.getRequestURI();
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        synchronized (window) {
            // 剔除滑出窗口的过期时间戳
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                writeTooManyRequests(response);
                return false;
            }
            window.addLast(now);
        }
        evictIdleKeys(now);
        return true;
    }

    /** 按请求路径返回对应限额；非限流路径返回 0（不限流） */
    private int limitFor(String uri) {
        if (uri.endsWith("/ai/chat")) {
            return aiChatPerMinute;
        }
        if (uri.endsWith("/ai/confirm")) {
            return aiConfirmPerMinute;
        }
        return 0;
    }

    /** 限流 key：登录用户用用户 ID，否则退回来源 IP */
    private String limitKey(HttpServletRequest request) {
        CurrentUser cu = CurrentUser.get();
        if (cu != null && cu.getUserId() != null) {
            return "u:" + cu.getUserId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * 顺带清理空闲窗口：最近一次请求早于 IDLE_EVICT_MS 的 key 整体移除。
     * 窗口数低于阈值时直接跳过，常规流量下近乎零开销。
     */
    private void evictIdleKeys(long now) {
        if (windows.size() < 1024) {
            return;
        }
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> e = it.next();
            Deque<Long> w = e.getValue();
            Long last = w.peekLast();
            if (last == null || now - last >= IDLE_EVICT_MS) {
                it.remove();
            }
        }
    }

    /** 429 响应：与全局 Result 结构保持一致，前端可按 code 统一提示 */
    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
    }
}
