-- V16：ai_messages 新增分支字段（A2 消息分支模型，对齐 chat-demo 的 branches）
-- 设计：
--   group_key  = 同一轮提问的分组标识。首轮为 NULL（旧数据兼容），从某条用户消息
--                「编辑重发」或某条助手消息「重新生成」起产生分支——新分支的 user
--                消息与 assistant 回复都带原轮的 group_key，同 key 的 user 消息
--                互为兄弟分支（分支导航「« 2/3 »」按此聚合）。
--   active     = 分支可见性开关。1=当前激活（历史接口返回），0=被切走的分支
--                （数据保留，切回时置回 1）。旧行默认 1，历史接口 SQL 加 active=1
--                过滤即向后兼容。
-- 纯追加列 + 默认值，可随时 DROP 回退。

ALTER TABLE ai_messages
    ADD COLUMN group_key VARCHAR(64) NULL COMMENT '同轮分支分组标识（编辑重发/重新生成产生的兄弟轮共享）',
    ADD COLUMN active TINYINT NOT NULL DEFAULT 1 COMMENT '1=当前激活分支 0=被切走的分支',
    ADD INDEX idx_ai_messages_group_key (conversation_id, group_key);
