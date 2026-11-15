-- V15：AI Agent 轨迹新增渠道尝试审计字段（B4，对齐 harness 的 attempt 留痕）
-- 说明：attempt_audit 存 JSON 数组字符串，按时间序记录本轮对话的渠道尝试链路：
--       ["try:0:硅基流动", "fail:0:硅基流动:429 rate limit", "try:1:DeepSeek", "switched:0:硅基流动->1:DeepSeek"]
--       事件类型：try=发起尝试 / fail=尝试失败（零输出且可切换才记录）/ switched=切换决策 /
--                 final-error=最终失败（所有候选耗尽或不可切换）。
--       纯追加列，NULL 兼容历史数据，可随时 DROP 回退。

ALTER TABLE ai_agent_traces
    ADD COLUMN attempt_audit TEXT NULL COMMENT '渠道尝试审计（JSON 数组：try/fail/switched/final-error 事件序）'
    AFTER approval_audit;
