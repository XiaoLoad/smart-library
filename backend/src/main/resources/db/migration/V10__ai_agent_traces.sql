-- V10：AI Agent 运行轨迹
-- 说明：每次 Agent 对话落一条轨迹，记录工具序列、决策旁白摘要、轮次、耗时、token 用量、
--       是否触发写操作门禁、是否触发循环降级。用于管理端「Agent 运行日志」页，
--       既是工程完备度的体现，也为技术文档提供可量化的运行数据。
-- 注：thought_summary 与 tool_sequence 存 JSON 数组字符串，便于前端直接解析展示。

CREATE TABLE IF NOT EXISTS ai_agent_traces (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL COMMENT '会话 ID',
  turn_id         VARCHAR(64) DEFAULT NULL COMMENT '轮次标识（同一会话内的一次提问-回答）',
  user_id         BIGINT      NOT NULL COMMENT '发起用户 ID',
  user_type       TINYINT     DEFAULT NULL COMMENT '用户类型：1=教师, 2=学生, 3=管理员',
  user_query      VARCHAR(500) DEFAULT NULL COMMENT '用户本轮提问（截断存储）',
  tool_sequence   TEXT        NULL COMMENT '工具调用序列（JSON 数组，如 ["queryBooks","applyBorrow"]）',
  thought_summary TEXT        NULL COMMENT '决策旁白摘要（JSON 数组，Agent 的规划轨迹）',
  tool_rounds     INT         NOT NULL DEFAULT 0 COMMENT '工具循环轮次（模型往返次数）',
  tool_count      INT         NOT NULL DEFAULT 0 COMMENT '工具调用总次数',
  gate_triggered  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否触发写操作授权门禁：1=是, 0=否',
  gate_tool       VARCHAR(64) DEFAULT NULL COMMENT '触发门禁的写工具名',
  degraded        TINYINT     NOT NULL DEFAULT 0 COMMENT '是否触发循环超限降级：1=是, 0=否',
  prompt_tokens   INT         DEFAULT NULL COMMENT '提示词 token 数',
  completion_tokens INT       DEFAULT NULL COMMENT '补全 token 数',
  duration_ms     BIGINT      DEFAULT NULL COMMENT '本轮总耗时（毫秒）',
  status          VARCHAR(16) NOT NULL DEFAULT 'success' COMMENT 'success=正常完成, degraded=降级, error=异常',
  error_msg       VARCHAR(500) DEFAULT NULL COMMENT '异常摘要',
  create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv (conversation_id),
  KEY idx_user (user_id),
  KEY idx_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
