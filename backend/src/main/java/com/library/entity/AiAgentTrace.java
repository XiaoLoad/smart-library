package com.library.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * M6a：AI Agent 运行轨迹。
 * <p>
 * 每次 Agent 对话落一条，用于管理端「Agent 运行日志」页展示与统计。
 * 既是工程完备度的体现，也为技术文档提供可量化的运行数据
 * （工具调用次数、人工授权次数、循环轮次、token 消耗等）。
 */
@Data
public class AiAgentTrace {
    private Long id;
    private Long conversationId;
    /** 轮次标识：同一会话内的一次「提问-回答」 */
    private String turnId;
    private Long userId;
    /** 用户类型：1=教师, 2=学生, 3=管理员 */
    private Integer userType;
    /** 用户本轮提问（截断存储） */
    private String userQuery;
    /** 工具调用序列（JSON 数组，如 ["queryBooks","applyBorrow"]） */
    private String toolSequence;
    /** 决策旁白摘要（JSON 数组，Agent 的规划轨迹） */
    private String thoughtSummary;
    /** 工具循环轮次（模型往返次数） */
    private Integer toolRounds;
    /** 工具调用总次数 */
    private Integer toolCount;
    /** 是否触发写操作授权门禁：1=是, 0=否 */
    private Integer gateTriggered;
    /** 触发门禁的写工具名 */
    private String gateTool;
    /** 是否触发循环超限降级：1=是, 0=否 */
    private Integer degraded;
    private Integer promptTokens;
    private Integer completionTokens;
    /** 本轮总耗时（毫秒） */
    private Long durationMs;
    /** success=正常完成, degraded=降级, error=异常 */
    private String status;
    private String errorMsg;
    private LocalDateTime createTime;
    /** 读者姓名（管理端列表联表带出，非表字段） */
    private String userRealName;
}
