package com.library.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * ThreadLocal 存储当前请求的用户信息
 */
@Data
@AllArgsConstructor
public class CurrentUser {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String username;
    private final Integer userType;

    public static void set(Long userId, String username, Integer userType) {
        HOLDER.set(new CurrentUser(userId, username, userType));
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
