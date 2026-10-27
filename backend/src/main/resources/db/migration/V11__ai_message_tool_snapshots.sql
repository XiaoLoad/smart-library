-- 消息级工具快照持久化：生成式 UI 内联卡片跨会话常驻
-- 每条助手消息附带本轮工具返回的可视化快照数组（JSON），
-- 前端加载历史消息时据此重建内联卡片；快照是查询时点数据，
-- 前端对历史卡片标注生成时间以示区分。
ALTER TABLE ai_messages
    ADD COLUMN tool_snapshots MEDIUMTEXT NULL COMMENT '工具快照数组（JSON：[{tool,type,data,createTime}]，生成式 UI 历史卡片重建用）' AFTER tool_summary;
