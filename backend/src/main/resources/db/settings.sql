USE library_db;
CREATE TABLE IF NOT EXISTS system_settings (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  setting_key   VARCHAR(50)  NOT NULL,
  setting_value TEXT         DEFAULT NULL,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统设置';

-- AI 渠道列表（多渠道，按 sort 取第一个 enabled 为生效渠道）
-- 注意：此处仅种入空列表占位；服务端在发现列表为空时会自动从旧库 ai_config 迁移，迁移后旧行删除，空种子行不会阻断迁移
INSERT INTO system_settings (setting_key, setting_value)
VALUES ('ai_channels', '{"channels":[]}')
ON DUPLICATE KEY UPDATE setting_value = setting_value;

-- 馆内规则：逾期罚款费率（元/天，按自然日计算）
INSERT INTO system_settings (setting_key, setting_value)
VALUES ('library_rules', '{"fineRatePerDay":0.50}')
ON DUPLICATE KEY UPDATE setting_value = setting_value;
