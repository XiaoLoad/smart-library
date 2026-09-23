-- ===== 智慧图书管理系统 · 数据库 DDL =====
-- MySQL 8.0+，字符集 utf8mb4

CREATE DATABASE IF NOT EXISTS library_db
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE library_db;

-- 用户表
DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  username     VARCHAR(50)  NOT NULL COMMENT '登录名',
  password     VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  real_name    VARCHAR(50)  NOT NULL,
  user_type    TINYINT      NOT NULL COMMENT '1教师 2学生 3管理员',
  role_name    VARCHAR(20)  NOT NULL,
  email        VARCHAR(100) DEFAULT NULL,
  phone        VARCHAR(20)  DEFAULT NULL,
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  avatar       VARCHAR(255) DEFAULT '',
  teacher_no   VARCHAR(30)  DEFAULT NULL,
  department   VARCHAR(60)  DEFAULT NULL,
  title        VARCHAR(30)  DEFAULT NULL COMMENT '教师职称',
  student_no   VARCHAR(30)  DEFAULT NULL,
  class_name   VARCHAR(30)  DEFAULT NULL,
  grade        VARCHAR(10)  DEFAULT NULL,
  major        VARCHAR(60)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_teacher_no (teacher_no),
  UNIQUE KEY uk_student_no (student_no),
  KEY idx_user_type (user_type),
  KEY idx_real_name (real_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分类表（三级树）
DROP TABLE IF EXISTS categories;
CREATE TABLE categories (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  parent_id      BIGINT      NOT NULL DEFAULT 0 COMMENT '0=根',
  category_name  VARCHAR(50) NOT NULL,
  category_code  VARCHAR(50) NOT NULL,
  level          TINYINT     NOT NULL COMMENT '1/2/3',
  sort_order     INT         NOT NULL DEFAULT 1,
  status         TINYINT     NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (category_code),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 图书表
DROP TABLE IF EXISTS books;
CREATE TABLE books (
  id              BIGINT         NOT NULL AUTO_INCREMENT,
  title           VARCHAR(100)   NOT NULL,
  author          VARCHAR(80)    NOT NULL,
  publisher       VARCHAR(100)   DEFAULT '',
  isbn            VARCHAR(20)    NOT NULL,
  category_id     BIGINT         NOT NULL,
  location        VARCHAR(50)    DEFAULT '',
  price           DECIMAL(10,2)  NOT NULL DEFAULT 0,
  publish_date    DATE           DEFAULT NULL,
  cover_url       VARCHAR(255)   DEFAULT '',
  description     VARCHAR(500)   DEFAULT '',
  total_count     INT            NOT NULL DEFAULT 1,
  available_count INT            NOT NULL DEFAULT 0,
  status          TINYINT        NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  create_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_isbn (isbn),
  KEY idx_category (category_id),
  KEY idx_title (title),
  KEY idx_author (author)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 借阅记录表
DROP TABLE IF EXISTS borrows;
CREATE TABLE borrows (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  borrow_no         VARCHAR(30)  NOT NULL,
  user_id           BIGINT       NOT NULL,
  user_name         VARCHAR(50)  NOT NULL,
  book_id           BIGINT       NOT NULL,
  book_title        VARCHAR(100) NOT NULL,
  apply_time        DATETIME     NOT NULL,
  borrow_time       DATETIME     DEFAULT NULL,
  due_time          DATETIME     DEFAULT NULL,
  return_time       DATETIME     DEFAULT NULL,
  renew_count       INT          NOT NULL DEFAULT 0,
  renew_max_count   INT          NOT NULL DEFAULT 1,
  pickup_deadline   DATETIME     DEFAULT NULL,
  return_request_time DATETIME   DEFAULT NULL COMMENT '用户申请归还时间',
  pickup_admin_id   BIGINT       DEFAULT NULL COMMENT '取书确认管理员',
  confirm_admin_id  BIGINT       DEFAULT NULL COMMENT '归还确认管理员',
  status            TINYINT     NOT NULL COMMENT '0待取书 1借阅中 2已归还 3已逾期 4已取消 5待确认归还',
  remark            VARCHAR(100) DEFAULT '',
  qr_payload        TEXT         DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_borrow_no (borrow_no),
  KEY idx_user (user_id),
  KEY idx_book (book_id),
  KEY idx_status (status),
  KEY idx_apply_time (apply_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 罚款表
DROP TABLE IF EXISTS fines;
CREATE TABLE fines (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  user_id           BIGINT        NOT NULL,
  user_name         VARCHAR(50)   NOT NULL,
  borrow_record_id  BIGINT        NOT NULL,
  borrow_no         VARCHAR(30)   NOT NULL,
  book_title        VARCHAR(100)  NOT NULL,
  amount            DECIMAL(10,2) NOT NULL,
  days_overdue      INT           NOT NULL,
  reason            VARCHAR(100)  DEFAULT '',
  status            TINYINT       NOT NULL COMMENT '0未缴 1已缴',
  paid_time         DATETIME      DEFAULT NULL,
  create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id),
  KEY idx_borrow_no (borrow_no),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 公告表
DROP TABLE IF EXISTS announcements;
CREATE TABLE announcements (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  title           VARCHAR(120) NOT NULL,
  content         TEXT         NOT NULL,
  publisher_id    BIGINT       NOT NULL,
  publisher_name  VARCHAR(50)  NOT NULL,
  top            TINYINT      NOT NULL DEFAULT 0,
  status          TINYINT     NOT NULL DEFAULT 1,
  publish_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_top_time (top, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 通知表
DROP TABLE IF EXISTS notifications;
CREATE TABLE notifications (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  user_id      BIGINT       NOT NULL,
  type         TINYINT      NOT NULL COMMENT '1系统 2超期 3罚款 4到期',
  title        VARCHAR(120) NOT NULL,
  content      VARCHAR(500) NOT NULL,
  is_read      TINYINT      NOT NULL DEFAULT 0,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 借阅额度规则表
DROP TABLE IF EXISTS borrow_limits;
CREATE TABLE borrow_limits (
  id               BIGINT  NOT NULL AUTO_INCREMENT,
  user_type        TINYINT NOT NULL,
  max_books        INT     NOT NULL,
  loan_days        INT     NOT NULL,
  renew_max_count  INT     NOT NULL,
  renew_days       INT     NOT NULL,
  pickup_days      INT     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_type (user_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI 对话表
DROP TABLE IF EXISTS ai_conversations;
CREATE TABLE ai_conversations (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  user_id      BIGINT       NOT NULL,
  title        VARCHAR(120) NOT NULL,
  model        VARCHAR(50)  NOT NULL DEFAULT 'qwen-plus',
  status       TINYINT      NOT NULL DEFAULT 1,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI 消息表
DROP TABLE IF EXISTS ai_messages;
CREATE TABLE ai_messages (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT       NOT NULL,
  role            VARCHAR(20)  NOT NULL COMMENT 'user/assistant',
  content         TEXT         NOT NULL,
  tool_summary    VARCHAR(512) NULL COMMENT '工具调用摘要（JSON 数组，按调用顺序记录工具名）',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
