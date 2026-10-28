-- AI 会话级写操作权限档位（M7）：
--   confirm = 逐笔确认（默认）：每个写工具执行前弹授权卡
--   batch   = 批量授权：同类写操作累积到本轮结束统一弹一张批量卡
--   auto    = 自动执行：写工具直接执行，仅展示回执与审计痕迹（仅管理员可用）
-- 档位挂在会话上而非用户全局，便于演示/日常/信任三种场景按会话切换；
-- 升档（confirm → batch/auto）仅管理员可操作，降档任何人都可自行调回。
ALTER TABLE ai_conversations
    ADD COLUMN permission_mode VARCHAR(16) NOT NULL DEFAULT 'confirm'
        COMMENT '写操作权限档位：confirm=逐笔确认 / batch=批量授权 / auto=自动执行（仅管理员）' AFTER status;
