-- M10 对齐 ZCode 权限交互模型：系统机制消息（[系统回执]/[系统续跑]）对用户隐藏
-- visibility: visible = 正常渲染；hidden = 仅落库供模型续跑上下文，历史接口不过滤返回
ALTER TABLE ai_messages
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'visible' COMMENT '可见性：visible/hidden（机制消息对 UI 隐藏）',
    ADD INDEX idx_ai_messages_visibility (conversation_id, visibility);

-- 存量机制消息回填为 hidden（[系统回执] 回执 / [系统续跑] 引导语）
UPDATE ai_messages SET visibility = 'hidden'
WHERE content LIKE '[系统回执]%' OR content LIKE '[系统续跑]%';
