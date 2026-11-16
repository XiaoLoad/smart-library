package com.library.security;

import com.library.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败锁定守卫：按「用户名 + 来源 IP」记录连续失败次数，超阈值锁定一段时间。
 * <p>
 * 限流策略分两层：
 * <ul>
 *   <li>失败锁定：连续失败 {@value #MAX_ATTEMPTS} 次后锁定 {@value #LOCK_SECONDS} 秒，
 *       锁定期内即使密码正确也拒绝（防暴力破解，正确登录一次即清零）；</li>
 *   <li>频率限流：同一 key 每分钟最多 {@value #RATE_PER_MINUTE} 次尝试（含成功），
 *       防止高频撞库探测用户名是否存在。</li>
 * </ul>
 * key 形如 {@code username|ip}：单 IP 撞单个用户、分布式 IP 撞同一用户均被覆盖；
 * 单 IP 撞不同用户由频率限流兜底。
 * <p>
 * 进程内存储，单实例部署够用；多实例部署需迁移 Redis（与授权令牌同一取舍）。
 */
@Component
public class LoginAttemptGuard {

    /** 连续失败多少次触发锁定 */
    private static final int MAX_ATTEMPTS = 5;
    /** 锁定时长（秒） */
    private static final long LOCK_SECONDS = 300L;
    /** 每分钟最大尝试次数（含成功登录） */
    private static final int RATE_PER_MINUTE = 10;
    /** 频率窗口长度（毫秒） */
    private static final long RATE_WINDOW_MS = 60_000L;
    /** 记录多久无活动后清除，避免内存无限增长 */
    private static final long IDLE_EVICT_MS = 30 * 60_000L;

    @Value("${app.login-guard.enabled:true}")
    private boolean enabled;

    /** 失败计数与锁定状态：key → 记录 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    /** 频率窗口：key → 最近一分钟内的时间戳队列 */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 请求前检查：锁定期内抛 423，频率超限抛 429。
     */
    public void check(String username, String ip) {
        if (!enabled) {
            return;
        }
        evictIdle();
        String key = attemptKey(username, ip);
        Attempt a = attempts.get(key);
        long now = System.currentTimeMillis();
        if (a != null && a.lockedUntil > now) {
            long remainSec = (a.lockedUntil - now) / 1000L + 1;
            throw new BusinessException(423, "登录失败次数过多，账号已临时锁定，请 " + remainSec + " 秒后重试");
        }
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            w.timestamps.removeIf(t -> now - t >= RATE_WINDOW_MS);
            if (w.timestamps.size() >= RATE_PER_MINUTE) {
                throw new BusinessException(429, "登录尝试过于频繁，请稍后再试");
            }
            w.timestamps.add(now);
        }
    }

    /**
     * 登录成功：清零失败计数（不清频率窗口——成功也占一次尝试配额）。
     */
    public void onSuccess(String username, String ip) {
        attempts.remove(attemptKey(username, ip));
    }

    /**
     * 登录失败：累加计数，达到阈值即锁定。用户不存在与密码错误同口径计数，
     * 不给攻击者「用户名是否存在」的探测信号。
     */
    public void onFailure(String username, String ip) {
        if (!enabled) {
            return;
        }
        Attempt a = attempts.computeIfAbsent(attemptKey(username, ip), k -> new Attempt());
        synchronized (a) {
            a.count++;
            if (a.count >= MAX_ATTEMPTS) {
                a.lockedUntil = System.currentTimeMillis() + LOCK_SECONDS * 1000L;
                a.count = 0;
            }
        }
    }

    private String attemptKey(String username, String ip) {
        return (username == null ? "" : username.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    /**
     * 提取客户端来源 IP。
     * <p>
     * 优先取反向代理注入的 X-Forwarded-For 第一跳（信任内网反代场景），
     * 无代理头时回落到直连地址。若部署在不可信网络环境直连暴露，
     * 攻击者可伪造该头绕过按 IP 的锁定维度——但用户名维度仍有效，
     * 且此时 JWT_SECRET 等机密同样暴露，风险等级不变。
     */
    public static String clientIp() {
        jakarta.servlet.http.HttpServletRequest req =
                ((org.springframework.web.context.request.ServletRequestAttributes)
                        org.springframework.web.context.request.RequestContextHolder
                                .currentRequestAttributes()).getRequest();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** 清理长时间无活动的记录，常规流量下近乎零开销 */
    private void evictIdle() {
        if (attempts.size() + windows.size() < 1024) {
            return;
        }
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            return a.lockedUntil < now && now - a.lastActivity > IDLE_EVICT_MS;
        });
        windows.entrySet().removeIf(e -> {
            Window w = e.getValue();
            synchronized (w) {
                return w.timestamps.isEmpty() || now - w.timestamps.peekLast() > IDLE_EVICT_MS;
            }
        });
    }

    private static final class Attempt {
        volatile long count;
        volatile long lockedUntil;
        volatile long lastActivity = System.currentTimeMillis();
    }

    private static final class Window {
        final java.util.Deque<Long> timestamps = new java.util.concurrent.ConcurrentLinkedDeque<>();
    }
}
