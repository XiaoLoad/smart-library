-- V14：AI Agent 轨迹新增写操作授权审计字段（B2 fail-closed 补强）
-- 说明：对齐 harness 的 approval/asked + approval/decided 审计事件对。
--       approval_audit 存 JSON 数组字符串，按时间序记录授权卡从签发到裁决的完整链路：
--       ["asked:applyBorrow", "decided:applyBorrow:allowed-once"]
--       outcome 取值：allowed-once=确认通过 / rejected=拒绝 / cancelled=取消 /
--                     expired=超时失效 / unavailable=裁决通道异常。
--       纯追加列，NULL 兼容历史数据，可随时 DROP 回退。

ALTER TABLE ai_agent_traces
    ADD COLUMN approval_audit TEXT NULL COMMENT '写操作授权审计事件对（JSON 数组：asked/decided 对）'
    AFTER thought_summary;
