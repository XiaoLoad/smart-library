ALTER TABLE ai_messages
    ADD COLUMN tool_summary VARCHAR(512) NULL COMMENT '工具调用摘要（JSON 数组，按调用顺序记录工具名）' AFTER content;
