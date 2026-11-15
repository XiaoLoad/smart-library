package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2：授权令牌服务审计与 fail-closed 行为单测。
 * <p>
 * 覆盖 asked/decided 审计事件对在签发、确认、取消、过期四条路径上的完整性——
 * 不出现「asked 之后再无下文」的悬空记录。
 */
class AiConfirmServiceTest {

    private AiConfirmService service;
    /** 审计记录：["asked:toolName"] / ["decided:toolName:outcome"] */
    private CopyOnWriteArrayList<String> audit;

    @BeforeEach
    void setUp() {
        service = new AiConfirmService(new ObjectMapper());
        audit = new CopyOnWriteArrayList<>();
        service.setAuditListener(10L, new AiConfirmService.ApprovalAuditListener() {
            @Override
            public void onAsked(String token, String toolName) {
                audit.add("asked:" + toolName);
            }

            @Override
            public void onDecided(String token, String toolName, String outcome) {
                audit.add("decided:" + toolName + ":" + outcome);
            }
        });
    }

    private String issue() {
        return service.issue(1L, 10L, "applyBorrow",
                Map.of("bookTitle", "围城"), "借阅《围城》", List.of());
    }

    @Test
    @DisplayName("签发记 asked；确认成功记 decided:allowed-once，事件对成对出现")
    void issueThenConsumeEmitsAskedAndAllowedOnce() {
        String token = issue();
        assertThat(audit).containsExactly("asked:applyBorrow");

        AiConfirmService.PendingConfirm pc = service.consume(token, 1L, "applyBorrow", 10L);
        assertThat(pc).isNotNull();
        assertThat(pc.toolName()).isEqualTo("applyBorrow");
        assertThat(pc.args()).containsEntry("bookTitle", "围城");
        assertThat(audit).containsExactly("asked:applyBorrow", "decided:applyBorrow:allowed-once");
    }

    @Test
    @DisplayName("用户取消记 decided:cancelled，令牌立即失效")
    void revokeEmitsCancelled() {
        String token = issue();
        service.revoke(token);

        assertThat(service.consume(token, 1L, "applyBorrow", 10L)).isNull();
        assertThat(audit).containsExactly("asked:applyBorrow", "decided:applyBorrow:cancelled");
    }

    /**
     * 用替换 pending 条目的方式构造场景（record 字段 final，无法反射改单字段）。
     * map 可访问：条目键为 token，替换整条记录不影响其余状态。
     */
    @SuppressWarnings("unchecked")
    private void replacePending(String token, AiConfirmService.PendingConfirm replacement)
            throws Exception {
        java.lang.reflect.Field f = AiConfirmService.class.getDeclaredField("pending");
        f.setAccessible(true);
        ((java.util.Map<String, AiConfirmService.PendingConfirm>) f.get(service))
                .put(token, replacement);
    }

    private AiConfirmService.PendingConfirm getPending(String token) throws Exception {
        java.lang.reflect.Field f = AiConfirmService.class.getDeclaredField("pending");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, AiConfirmService.PendingConfirm> map =
                (java.util.Map<String, AiConfirmService.PendingConfirm>) f.get(service);
        return map.get(token);
    }

    @Test
    @DisplayName("过期令牌 consume 时记 decided:expired 并清除")
    void expiredConsumeEmitsExpired() throws Exception {
        String token = issue();
        AiConfirmService.PendingConfirm pc = getPending(token);
        // 以同一 token 重登一条 createdAt 在 61 秒前的记录（TTL=60s），构造过期令牌
        replacePending(token, new AiConfirmService.PendingConfirm(
                pc.token(), pc.userId(), pc.conversationId(), pc.toolName(),
                pc.args(), pc.argsDigest(), pc.title(), pc.rows(),
                System.currentTimeMillis() - 61_000L));

        assertThat(service.consume(token, 1L, "applyBorrow", 10L)).isNull();
        assertThat(audit).containsExactly("asked:applyBorrow", "decided:applyBorrow:expired");
    }

    @Test
    @DisplayName("用户身份不匹配：拒绝但不消费令牌、不记 decided（rightful 用户仍可确认）")
    void wrongUserRejectsWithoutConsuming() {
        String token = issue();
        assertThat(service.consume(token, 2L, "applyBorrow", 10L)).isNull();
        // 只有 asked，无 decided——令牌未被裁决
        assertThat(audit).containsExactly("asked:applyBorrow");
        assertThat(service.consume(token, 1L, "applyBorrow", 10L)).isNotNull();
    }

    @Test
    @DisplayName("工具名不匹配：拒绝但不消费（防拿 A 工具令牌执行 B）")
    void wrongToolRejectsWithoutConsuming() {
        String token = issue();
        assertThat(service.consume(token, 1L, "requestReturn", 10L)).isNull();
        assertThat(audit).containsExactly("asked:applyBorrow");
    }

    @Test
    @DisplayName("会话不匹配：拒绝但不消费（防跨会话重放）")
    void wrongConversationRejectsWithoutConsuming() {
        String token = issue();
        assertThat(service.consume(token, 1L, "applyBorrow", 99L)).isNull();
        assertThat(audit).containsExactly("asked:applyBorrow");
    }

    @Test
    @DisplayName("参数摘要校验兜底：快照参数被外部篡改后 consume 拒绝")
    void tamperedArgsDigestRejected() throws Exception {
        String token = issue();
        AiConfirmService.PendingConfirm pc = getPending(token);
        // 以同一 token 重登一条 args 被篡改的记录（摘要基于原参数，校验应拒绝）
        replacePending(token, new AiConfirmService.PendingConfirm(
                pc.token(), pc.userId(), pc.conversationId(), pc.toolName(),
                Map.of("bookTitle", "被篡改的书"), pc.argsDigest(), pc.title(), pc.rows(),
                pc.createdAt()));
        assertThat(service.consume(token, 1L, "applyBorrow", 10L)).isNull();
        assertThat(audit).containsExactly("asked:applyBorrow");
    }

    @Test
    @DisplayName("容量上限 fail-closed：1000 条待确认时拒绝新授权请求")
    void capacityGuardRejectsIssue() {
        for (int i = 0; i < 1000; i++) {
            service.issue((long) i, 10L, "applyBorrow", Map.of(), "t" + i, List.of());
        }
        assertThatThrownBy(() -> service.issue(1L, 10L, "applyBorrow", Map.of(), "x", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权请求过多");
    }

    @Test
    @DisplayName("审计回调抛异常不影响主流程（回调吞异常）")
    void auditListenerFailureDoesNotBreakIssue() {
        AiConfirmService s = new AiConfirmService(new ObjectMapper());
        s.setAuditListener(10L, new AiConfirmService.ApprovalAuditListener() {
            @Override
            public void onAsked(String token, String toolName) {
                throw new RuntimeException("audit down");
            }

            @Override
            public void onDecided(String token, String toolName, String outcome) {
                throw new RuntimeException("audit down");
            }
        });
        String token = s.issue(1L, 10L, "applyBorrow", Map.of(), "t", List.of());
        assertThat(token).startsWith("cfm_");
        assertThat(s.consume(token, 1L, "applyBorrow", 10L)).isNotNull();
    }
}
