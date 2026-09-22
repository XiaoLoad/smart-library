-- V5：微信小程序登录绑定表
CREATE TABLE IF NOT EXISTS wechat_accounts (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL COMMENT '绑定的系统用户',
  openid      VARCHAR(64) NOT NULL COMMENT '微信小程序 openid',
  unionid     VARCHAR(64) DEFAULT NULL,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_openid (openid),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
