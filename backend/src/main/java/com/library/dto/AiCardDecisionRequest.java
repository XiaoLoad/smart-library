package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 授权卡决策回执（M9）：前端在用户对授权卡做出决策（确认/取消/过期）后回传，
 * 服务端把卡片决策态存入消息的快照通道，历史消息据此重建只读授权卡。
 * <p>
 * 安全性说明：本接口只做<b>展示层记录</b>，不执行任何写操作——真正的写操作
 * 只能由 /ai/confirm 按服务端保存的令牌参数执行，此处回传的 rows 等内容
 * 仅用于渲染历史卡片。
 */
@Data
public class AiCardDecisionRequest {

    /** 会话 ID */
    private Long conversationId;

    /** 决策：confirmed / cancelled / expired */
    @NotBlank(message = "缺少决策类型")
    private String decision;

    /** 授权工具名 */
    @NotBlank(message = "缺少工具名")
    private String tool;

    /** 授权卡标题 */
    private String title;

    /** 授权卡参数明细（渲染历史卡片用） */
    private List<Map<String, String>> rows;

    /** 批量卡标记：true 表示本条是批量授权卡的决策回执 */
    private Boolean batch;
}
