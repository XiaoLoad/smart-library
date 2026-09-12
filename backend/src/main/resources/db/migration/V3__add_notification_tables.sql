-- 催还通知系统：规则配置 / 发送日志 / 邮件配置，并扩展 notifications 表
CREATE TABLE notification_rule (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_type   VARCHAR(20)  NOT NULL COMMENT 'DUE_SOON 即将逾期 / OVERDUE_D1/D3/D7 逾期第N天',
  channel      VARCHAR(10)  NOT NULL COMMENT 'INAPP 站内 / EMAIL 邮件 / BOTH 两者',
  enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  offset_days  INT          NOT NULL DEFAULT 0 COMMENT 'DUE_SOON 用负数(应还前N天)；OVERDUE 用正数(逾期N天)',
  subject_tpl  VARCHAR(200) NOT NULL DEFAULT '' COMMENT '邮件主题模板，支持占位符',
  body_tpl     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '正文模板，支持 {readerName}{bookTitle}{dueDate}{borrowNo}{daysOverdue}',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO notification_rule (event_type, channel, enabled, offset_days, subject_tpl, body_tpl) VALUES
('DUE_SOON',   'BOTH', 1, -3, '图书即将到期提醒', '亲爱的{readerName}：您借阅的《{bookTitle}》（借阅号 {borrowNo}）将于 {dueDate} 到期，请按时归还。'),
('OVERDUE_D1', 'BOTH', 1, 1,  '图书已逾期提醒',   '亲爱的{readerName}：您借阅的《{bookTitle}》（借阅号 {borrowNo}）已逾期 {daysOverdue} 天，请尽快归还，以免产生滞纳金。'),
('OVERDUE_D3', 'BOTH', 1, 3,  '图书逾期催告',     '亲爱的{readerName}：您借阅的《{bookTitle}》（借阅号 {borrowNo}）已逾期 {daysOverdue} 天，请尽快到馆归还或办理续借。'),
('OVERDUE_D7', 'BOTH', 1, 7,  '图书严重逾期催告', '亲爱的{readerName}：您借阅的《{bookTitle}》（借阅号 {borrowNo}）已逾期 {daysOverdue} 天，长期逾期将影响后续借阅权限，请立即处理。');

CREATE TABLE notification_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  borrow_no   VARCHAR(30)  NOT NULL,
  user_id     BIGINT       NOT NULL,
  event_type  VARCHAR(20)  NOT NULL,
  channel     VARCHAR(10)  NOT NULL COMMENT 'INAPP / EMAIL',
  status      VARCHAR(10)  NOT NULL COMMENT 'PENDING/SENT/FAILED/SKIPPED',
  to_email    VARCHAR(100) DEFAULT '',
  subject     VARCHAR(200) DEFAULT '',
  content     VARCHAR(1000) DEFAULT '',
  error_msg   VARCHAR(500) DEFAULT '',
  sent_at     DATETIME     DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  rule_id     BIGINT       DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dedup (borrow_no, event_type, channel),
  KEY idx_user (user_id),
  KEY idx_status (status),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mail_config (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  smtp_host   VARCHAR(100) NOT NULL DEFAULT '',
  smtp_port   INT          NOT NULL DEFAULT 25,
  username    VARCHAR(100) NOT NULL DEFAULT '',
  password    VARCHAR(200) NOT NULL DEFAULT '' COMMENT '建议加密存储',
  from_addr   VARCHAR(100) NOT NULL DEFAULT '',
  use_tls     TINYINT      NOT NULL DEFAULT 1,
  enabled     TINYINT      NOT NULL DEFAULT 0 COMMENT '1启用邮件渠道 0停用',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO mail_config (id, smtp_host, smtp_port, username, password, from_addr, use_tls, enabled)
VALUES (1, '', 465, '', '', '', 1, 0);

ALTER TABLE notifications
  ADD COLUMN event_type     TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通 1即将逾期 2已逾期',
  ADD COLUMN ref_borrow_no  VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '关联借阅单号';
