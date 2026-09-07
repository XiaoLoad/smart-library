USE library_db;
CREATE TABLE IF NOT EXISTS system_settings (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  setting_key   VARCHAR(50)  NOT NULL,
  setting_value TEXT         DEFAULT NULL,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统设置';

INSERT INTO system_settings (setting_key, setting_value)
VALUES ('ai_config', '{"provider":"deepseek","apiKey":"","model":"deepseek-chat","endpoint":"https://api.deepseek.com/v1/chat/completions"}')
ON DUPLICATE KEY UPDATE setting_value = setting_value;
