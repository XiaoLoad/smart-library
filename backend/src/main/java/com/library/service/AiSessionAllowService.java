package com.library.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级写操作白名单（M8，授权卡「本会话始终允许」）。
 * <p>
 * 用户在授权卡上勾选「始终允许」确认后，把 {会话ID → 工具名} 登记到白名单；
 * 本会话后续同类写操作由门禁直接放行，不再逐次弹卡。粒度是会话而非全局：
 * 换会话/重启服务（内存存储）后白名单自然失效，风险有界。
 * <p>
 * 与档位的关系：白名单是单卡上的临时放行选择，优先级高于档位判断——
 * confirm 档下勾选了「始终允许」的工具也直接执行；auto 档本就不拦截。
 */
@Service
public class AiSessionAllowService {

    private static final int MAX_ENTRIES = 500;

    /** 白名单：conversationId → 已放行的工具名集合 */
    private final Map<Long, Set<String>> allowed = new ConcurrentHashMap<>();

    /**
     * 登记放行项。
     *
     * @return true 登记成功；false 容量已满（放弃登记，不影响本次确认）
     */
    public boolean allow(Long conversationId, String toolName) {
        if (conversationId == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        int total = allowed.values().stream().mapToInt(Set::size).sum();
        if (total >= MAX_ENTRIES) {
            return false;
        }
        allowed.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
        return true;
    }

    /** 查询该会话该工具是否已被「始终允许」 */
    public boolean isAllowed(Long conversationId, String toolName) {
        if (conversationId == null || toolName == null) {
            return false;
        }
        Set<String> tools = allowed.get(conversationId);
        return tools != null && tools.contains(toolName);
    }

    /** 用户显式撤销（预留：撤销入口可在设置页提供） */
    public void revoke(Long conversationId, String toolName) {
        Set<String> tools = allowed.get(conversationId);
        if (tools != null) {
            tools.remove(toolName);
        }
    }

    /** 会话删除时清空其白名单 */
    public void clearConversation(Long conversationId) {
        if (conversationId != null) {
            allowed.remove(conversationId);
        }
    }
}
