package com.library.service;

import com.library.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量授权服务（M7，batch 档位）。
 * <p>
 * 与 {@link AiConfirmService} 的单笔令牌不同，批量授权把<b>本轮多个写操作</b>打包成
 * 一个批次：前端只弹一张批量授权卡（rows 汇总各项明细），用户确认后服务端按顺序
 * 执行全部操作，逐项返回结果。
 * <p>
 * 安全模型与单笔一致：客户端只回传批次令牌，参数全部取自服务端保存的副本；
 * 部分失败不影响已成功项（各项独立 try-catch），结果如实逐项回报。
 */
@Service
public class AiBatchConfirmService {

    /** 批次有效期（秒）：与单笔授权卡一致 */
    private static final long BATCH_TTL_SECONDS = 120L;

    private final SecureRandomHolder random = new SecureRandomHolder();
    /** 待确认批次：token → 批次 */
    private final Map<String, PendingBatch> pending = new ConcurrentHashMap<>();

    /** 批次中的一项写操作 */
    public record BatchItem(String toolName, String title,
                            List<Map<String, String>> rows, Map<String, Object> args) {
    }

    /**
     * 待确认批次：令牌 + 本轮收集到的全部写操作。
     */
    public record PendingBatch(String token, Long userId, Long conversationId,
                               List<BatchItem> items, long createdAt) {
    }

    /**
     * 登记批量授权请求。
     *
     * @param userId         发起用户
     * @param conversationId 会话 ID
     * @param items          本轮收集到的写操作列表（非空）
     * @return 不透明批次令牌
     */
    public String issue(Long userId, Long conversationId, List<BatchItem> items) {
        purgeExpired();
        if (pending.size() >= 200) {
            throw new BusinessException(429, "授权请求过多，请稍后再试");
        }
        String token = "bat_" + random.token();
        pending.put(token, new PendingBatch(token, userId, conversationId,
                List.copyOf(items), System.currentTimeMillis()));
        return token;
    }

    /**
     * 校验并消费批次（一次性）。校验项与单笔一致：存在性、有效期、用户、会话。
     * 工具名不校验——批量卡是整批确认，不存在「拿 A 工具令牌执行 B」的单项混淆面。
     *
     * @return 校验通过的批次；失败返回 null
     */
    public PendingBatch consume(String token, Long userId, Long expectedConversationId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PendingBatch pb = pending.get(token);
        if (pb == null) {
            return null;
        }
        if (System.currentTimeMillis() - pb.createdAt() > BATCH_TTL_SECONDS * 1000L) {
            pending.remove(token);
            return null;
        }
        if (pb.userId() == null || !pb.userId().equals(userId)) {
            return null;
        }
        if (expectedConversationId != null && !expectedConversationId.equals(pb.conversationId())) {
            return null;
        }
        pending.remove(token);
        return pb;
    }

    /** 用户取消时作废批次 */
    public void revoke(String token) {
        if (token != null) {
            pending.remove(token);
        }
    }

    private void purgeExpired() {
        pending.entrySet().removeIf(e ->
                System.currentTimeMillis() - e.getValue().createdAt() > BATCH_TTL_SECONDS * 1000L);
    }

    /** 简易 SecureRandom 持有者（与 AiConfirmService 同样的 256 位熵令牌） */
    private static class SecureRandomHolder {
        private final java.security.SecureRandom random = new java.security.SecureRandom();

        String token() {
            byte[] buf = new byte[32];
            random.nextBytes(buf);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        }
    }

    /** 供 AiService 组装批量卡 rows 用 */
    public static List<Map<String, String>> mergeRows(List<BatchItem> items) {
        List<Map<String, String>> rows = new ArrayList<>();
        int idx = 1;
        for (BatchItem item : items) {
            rows.add(singleRow("操作 " + idx++, item.toolName()));
            if (item.rows() != null) {
                rows.addAll(item.rows());
            }
        }
        return rows;
    }

    private static Map<String, String> singleRow(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value == null ? "" : value);
        return m;
    }
}
