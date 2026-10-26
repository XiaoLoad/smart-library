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
        private final long startAt = System.currentTimeMillis();
        private volatile boolean gateTriggered = false;
        private volatile String gateTool = null;
        private volatile boolean degraded = false;
        private volatile Integer promptTokens = null;
        private volatile Integer completionTokens = null;
        private volatile String errorMsg = null;

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
            t.setToolRounds(c.toolSequence.isEmpty() ? 0 : c.toolSequence.size());
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
