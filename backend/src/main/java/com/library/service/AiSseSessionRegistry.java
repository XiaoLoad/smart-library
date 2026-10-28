package com.library.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M10：会话级 SSE 通道注册表。
 * <p>
 * 对齐 ZCode 的设计：权限确认不在 SSE 流内（独立 POST），但确认执行的结果需要
 * 推回前端把授权卡原地更新为终态。chat() 建立流时把 emitter 登记到本注册表，
 * confirm 执行完成后按会话 ID 取活跃通道推 tool_result 事件。
 * <p>
 * 单实例内存实现；每会话保留最新 emitter（同会话多开页面时旧连接自然失效）。
 */
public final class AiSseSessionRegistry {

    private static final Map<Long, SseEmitter> SESSIONS = new ConcurrentHashMap<>();

    private AiSseSessionRegistry() {
    }

    /** 登记会话的活跃 SSE 通道（新连接覆盖旧连接） */
    public static void register(Long conversationId, SseEmitter emitter) {
        if (conversationId != null && emitter != null) {
            SESSIONS.put(conversationId, emitter);
        }
    }

    /** 取会话的活跃通道；无则返回 null */
    public static SseEmitter get(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        SseEmitter emitter = SESSIONS.get(conversationId);
        if (emitter == null) {
            return null;
        }
        // 流结束/超时/出错后通道失效，探测失败即移除
        try {
            emitter.send(SseEmitter.event().comment("ping"));
            return emitter;
        } catch (Exception e) {
            SESSIONS.remove(conversationId, emitter);
            return null;
        }
    }

    /** 会话流结束时注销 */
    public static void unregister(Long conversationId, SseEmitter emitter) {
        if (conversationId != null && emitter != null) {
            SESSIONS.remove(conversationId, emitter);
        }
    }
}
