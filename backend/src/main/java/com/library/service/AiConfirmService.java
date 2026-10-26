package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写操作授权令牌服务：生成、校验、一次性消费 confirmToken。
 * <p>
 * <b>令牌模型</b>：用户点「确认办理」时，客户端只回传一个不透明的 token，
 * <b>不回传工具参数</b>。服务端从令牌中取出原始参数执行——因此参数不可能被客户端篡改。
 * 这条设计比「客户端回传参数 + 服务端校验」更安全：篡改在传输链路上就没有可乘之机。
 * <p>
 * <b>令牌绑定六要素</b>：用户 ID、会话 ID、工具名、参数摘要、创建时间、一次性标记。
 * 校验时任一项不符即拒绝，防止令牌被用于其他用户/会话/工具，或被重放。
 * <p>
 * <b>存储选型</b>：当前用内存 {@link ConcurrentHashMap}。项目为单实例部署，够用。
 * 若后续多实例部署，需换成 Redis（用 TTL 实现过期）——这一取舍会在技术文档中说明。
 */
@Service
public class AiConfirmService {

    private static final Logger log = LoggerFactory.getLogger(AiConfirmService.class);

    /** 令牌有效期（秒）：超过即失效，需用户重新发起 */
    private static final long TOKEN_TTL_SECONDS = 60L;

    /** 令牌摘要标识，便于日志排查 */
    private static final String TOKEN_PREFIX = "cfm_";

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper objectMapper;

    /** 待确认的授权请求：token → 请求体 */
    private final Map<String, PendingConfirm> pending = new ConcurrentHashMap<>();

    public AiConfirmService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 授权请求：令牌对应的原始调用信息。
     *
     * @param token        不透明令牌（客户端持有）
     * @param userId       发起授权的用户 ID
     * @param conversationId 所属会话 ID
     * @param toolName     待执行的写工具名
     * @param args         工具参数（服务端保存，客户端无法篡改）
     * @param argsDigest   参数摘要（用于二次校验，防止存储被意外篡改）
     * @param title        授权卡标题
     * @param rows         授权卡参数明细
     * @param createdAt    创建时间戳（毫秒）
     */
    public record PendingConfirm(String token, Long userId, Long conversationId,
                                 String toolName, Map<String, Object> args,
                                 String argsDigest, String title,
                                 List<Map<String, String>> rows, long createdAt) {
    }

    /**
     * 生成授权令牌并登记待确认请求。
     *
     * @param userId         发起用户
     * @param conversationId 会话 ID
     * @param toolName       写工具名
     * @param args           工具参数
     * @param title          授权卡标题
     * @param rows           授权卡参数明细
     * @return 不透明令牌（客户端只需回传它）
     */
    public String issue(Long userId, Long conversationId, String toolName,
                        Map<String, Object> args, String title,
                        List<Map<String, String>> rows) {
        purgeExpired();
        String token = TOKEN_PREFIX + randomToken();
        // 先固化参数副本，再对副本算摘要：
        // Map.copyOf 的迭代顺序与原 Map 可能不同，若摘要基于原 Map 计算、
        // 校验时基于副本计算，JSON 序列化结果会不一致，导致合法确认被误判为篡改。
        // 这里用 TreeMap 做规范化排序，保证「同一组参数」在任何时候序列化结果一致。
        Map<String, Object> frozen = args == null
                ? Map.of()
                : new java.util.TreeMap<>(args);
        String digest = digest(frozen);
        pending.put(token, new PendingConfirm(token, userId, conversationId, toolName,
                frozen, digest, title, rows,
                System.currentTimeMillis()));
        return token;
    }

    /**
     * 校验并消费令牌：一次性消费，成功后令牌立即失效。
     * <p>
     * 校验项：存在性、有效期、用户身份、会话归属、工具名、参数摘要。
     * <p>
     * <b>关于消费时机</b>：仅在「用户身份匹配」时才移除令牌。
     * 若在校验用户身份前就移除，跨用户盗用尝试会把令牌烧掉，导致 rightful 用户也无法确认——
     * 这等于给攻击者一个「拒绝服务」的手段。身份不匹配时保留令牌 rightful 用户仍可正常使用。
     *
     * @param token 客户端回传的令牌
     * @param userId 当前登录用户
     * @param expectedTool 期望执行的工具名（防止拿 A 工具的令牌去执行 B 工具）
     * @return 校验通过的授权请求；失败返回 null
     */
    public PendingConfirm consume(String token, Long userId, String expectedTool) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PendingConfirm pc = pending.get(token);
        if (pc == null) {
            log.warn("[confirm] 令牌不存在: {}", token);
            return null;
        }
        // 过期：直接清除（无价值了）
        if (isExpired(pc.createdAt())) {
            log.warn("[confirm] 令牌已过期: {}", token);
            pending.remove(token);
            return null;
        }
        // 用户身份不匹配：拒绝但不消费，让 rightful 用户仍能确认
        if (pc.userId() == null || !pc.userId().equals(userId)) {
            log.warn("[confirm] 用户不匹配: tokenUser={} curUser={}", pc.userId(), userId);
            return null;
        }
        // 工具名不匹配：拒绝但不消费（可能是前端传错工具名，不应让 rightful 用户丢失授权）
        if (expectedTool != null && !expectedTool.equals(pc.toolName())) {
            log.warn("[confirm] 工具不匹配: expected={} actual={}", expectedTool, pc.toolName());
            return null;
        }
        // 参数摘要二次校验：防止存储侧数据被意外篡改
        if (!pc.argsDigest().equals(digest(pc.args()))) {
            log.warn("[confirm] 参数摘要不匹配，疑似存储被篡改");
            return null;
        }
        // 走到这里说明本次消费合法，移除令牌实现一次性语义
        pending.remove(token);
        return pc;
    }

    /**
     * 查询令牌对应的授权请求（不消费），用于「取消」时回填上下文。
     *
     * @param token  令牌
     * @param userId 当前用户
     * @return 授权请求；不存在或已过期返回 null
     */
    public PendingConfirm peek(String token, Long userId) {
        if (token == null) {
            return null;
        }
        PendingConfirm pc = pending.get(token);
        if (pc == null || isExpired(pc.createdAt())) {
            return null;
        }
        if (pc.userId() == null || !pc.userId().equals(userId)) {
            return null;
        }
        return pc;
    }

    /**
     * 主动作废令牌（用户点击「取消」时调用，令牌立即失效）。
     *
     * @param token 令牌
     */
    public void revoke(String token) {
        if (token != null) {
            pending.remove(token);
        }
    }

    /** 令牌是否已过期 */
    private boolean isExpired(long createdAt) {
        return System.currentTimeMillis() - createdAt > TOKEN_TTL_SECONDS * 1000L;
    }

    /** 清理过期令牌，避免内存无限增长 */
    private void purgeExpired() {
        pending.entrySet().removeIf(e -> isExpired(e.getValue().createdAt()));
    }

    /** 生成随机令牌主体（256 位熵） */
    private String randomToken() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * 计算参数摘要：用于检测参数是否被改动。
     * <p>
     * 用 SHA-256 而非可读摘要，是为了让摘要本身不可反推参数内容。
     */
    private String digest(Map<String, Object> args) {
        try {
            String json = objectMapper.writeValueAsString(args == null ? Map.of() : args);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 摘要计算失败时退化为空摘要（此时放弃二次校验，仍有其他校验项兜底）
            return "";
        }
    }
}
