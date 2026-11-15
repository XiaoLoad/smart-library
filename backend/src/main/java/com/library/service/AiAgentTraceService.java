package com.library.service;

import com.library.entity.AiAgentTrace;
import com.library.mapper.AiAgentTraceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M6a：Agent 运行轨迹服务。
 * <p>
 * 每次 Agent 对话结束（正常完成 / 降级 / 异常）落一条轨迹，记录工具序列、
 * 决策旁白、循环轮次、耗时、token 用量与安全事件（门禁触发、循环降级）。
 * <p>
 * 埋点失败绝不影响主流程——轨迹是观测手段，不能因为写库失败而让用户对话出错。
 */
@Service
public class AiAgentTraceService {

    @Autowired
    private AiAgentTraceMapper traceMapper;

    /** 一次对话的轨迹收集器：随对话生命周期累积，结束时落库 */
    public static class Collector {
        private final Long conversationId;
        private final Long userId;
        private final Integer userType;
        private final String userQuery;
        private final String turnId = UUID.randomUUID().toString();
        private final java.util.List<String> toolSequence = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<String> thoughtSummary = new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * B2：授权审计事件对（对齐 harness 的 approval/asked + approval/decided）。
         * 每项格式 "asked:toolName" / "decided:toolName:outcome"，
         * 按时间顺序落 JSON 数组，管理端可完整回放授权链路。
         */
        private final java.util.List<String> approvalAudit = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final long startAt = System.currentTimeMillis();
        private volatile boolean gateTriggered = false;
        private volatile String gateTool = null;
        private volatile boolean degraded = false;
        private volatile Integer promptTokens = null;
        private volatile Integer completionTokens = null;
        private volatile String errorMsg = null;
        /** 实际工具循环轮次：由 AiService 在对话结束时回写（框架口径），未回写时为 0 */
        private volatile int toolRounds = 0;

        public Collector(Long conversationId, Long userId, Integer userType, String userQuery) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.userType = userType;
            this.userQuery = userQuery;
        }

        public void recordTool(String name) {
            if (name != null && !name.isBlank()) {
                toolSequence.add(name);
            }
        }

        public void recordThought(String text) {
            if (text != null && !text.isBlank()) {
                thoughtSummary.add(text);
            }
        }

        /** B2：授权请求已发起（asked 事件） */
        public void recordApprovalAsked(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                approvalAudit.add("asked:" + toolName);
            }
        }

        /** B2：授权已裁决（decided 事件，outcome = allowed-once/rejected/cancelled/expired/unavailable） */
        public void recordApprovalDecided(String toolName, String outcome) {
            if (toolName != null && !toolName.isBlank() && outcome != null && !outcome.isBlank()) {
                approvalAudit.add("decided:" + toolName + ":" + outcome);
            }
        }

        public List<String> getApprovalAudit() {
            return approvalAudit;
        }

        public void markGate(String toolName) {
            this.gateTriggered = true;
            this.gateTool = toolName;
        }

        public void markDegraded() {
            this.degraded = true;
        }

        public void markError(String msg) {
            this.errorMsg = msg == null ? null : (msg.length() > 500 ? msg.substring(0, 500) : msg);
        }

        public void setTokenUsage(Integer prompt, Integer completion) {
            this.promptTokens = prompt;
            this.completionTokens = completion;
        }

        /** 回写实际工具循环轮次（框架口径的计数，见 AiService#chat 的 toolRounds） */
        public void setToolRounds(int rounds) {
            this.toolRounds = rounds;
        }

        public int getToolRounds() {
            return toolRounds;
        }

        /** 本轮唯一标识（decided 补录按它定位已落库的轨迹行） */
        public String getTurnId() {
            return turnId;
        }

        public List<String> getToolSequence() {
            return toolSequence;
        }

        public List<String> getThoughtSummary() {
            return thoughtSummary;
        }

        public boolean isGateTriggered() {
            return gateTriggered;
        }

        public String getGateTool() {
            return gateTool;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public long getDurationMs() {
            return System.currentTimeMillis() - startAt;
        }
    }

    /**
     * 新建收集器。
     */
    public Collector newCollector(Long conversationId, Long userId, Integer userType, String userQuery) {
        String q = userQuery == null ? null : (userQuery.length() > 500 ? userQuery.substring(0, 500) : userQuery);
        return new Collector(conversationId, userId, userType, q);
    }

    /**
     * 落库轨迹。任何异常都吞掉——观测失败不影响主流程。
     */
    public void save(Collector c) {
        if (c == null) {
            return;
        }
        try {
            AiAgentTrace t = new AiAgentTrace();
            t.setConversationId(c.conversationId);
            t.setTurnId(c.turnId);
            t.setUserId(c.userId);
            t.setUserType(c.userType);
            t.setUserQuery(c.userQuery);
            t.setToolSequence(toJsonArray(c.toolSequence));
            t.setThoughtSummary(toJsonArray(c.thoughtSummary));
            t.setApprovalAudit(toJsonArray(c.getApprovalAudit()));
            // 轮次取实际框架计数（至少 1：用到工具即视为 1 轮），工具数仍按工具调用次数
            t.setToolRounds(Math.max(1, c.getToolRounds()));
            t.setToolCount(c.toolSequence.size());
            t.setGateTriggered(c.isGateTriggered() ? 1 : 0);
            t.setGateTool(c.getGateTool());
            t.setDegraded(c.isDegraded() ? 1 : 0);
            t.setPromptTokens(c.promptTokens);
            t.setCompletionTokens(c.completionTokens);
            t.setDurationMs(c.getDurationMs());
            t.setStatus(c.errorMsg != null ? "error" : (c.isDegraded() ? "degraded" : "success"));
            t.setErrorMsg(c.errorMsg);
            t.setCreateTime(LocalDateTime.now());
            traceMapper.insert(t);
        } catch (Exception e) {
            // 轨迹写入失败不影响对话
        }
    }

    /**
     * B2：向已落库的轨迹追加一条授权审计事件（decided 补录）。
     * <p>
     * 用户点授权卡发生在流结束、轨迹已落库之后，此时内存收集器已失效——
     * 裁决结果按 turnId 直接 UPDATE 到 DB 行上。查询不到该轮轨迹时静默跳过。
     * 调用方负责组装条目文本（如 "decided:applyBorrow:allowed-once"）。
     */
    public void appendAuditEvent(String turnId, String entry) {
        if (turnId == null || turnId.isBlank() || entry == null || entry.isBlank()) {
            return;
        }
        try {
            traceMapper.appendApprovalAudit(turnId, entry);
        } catch (Exception e) {
            // 审计补录失败不影响确认主流程
        }
    }

    /** 把字符串列表序列化为 JSON 数组文本 */
    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    // —— 管理端查询 ——

    public List<AiAgentTrace> page(int offset, int limit, String status, String keyword) {
        return traceMapper.selectPage(offset, limit, status, keyword);
    }

    public int count(String status, String keyword) {
        return traceMapper.countAll(status, keyword);
    }

    public AiAgentTrace get(Long id) {
        return traceMapper.selectById(id);
    }

    public Map<String, Object> summary() {
        return traceMapper.selectSummary();
    }
}
