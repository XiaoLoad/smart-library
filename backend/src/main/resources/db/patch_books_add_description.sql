-- =============================================================
-- 增量补丁：为 books 表补充「内容简介」字段 description
-- -------------------------------------------------------------
-- 适用场景：手动建库（spring.sql.init.mode=never）的环境中，
-- books 表缺少 description 列，导致图书详情无简介、
-- 新增/编辑图书时报 Unknown column 'description' 错误。
--
-- 本脚本幂等，可重复执行：列已存在时自动跳过，不会报错。
-- 用法：mysql -u root -p library_db < patch_books_add_description.sql
-- =============================================================

USE library_db;

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'books'
      AND COLUMN_NAME = 'description'
);

SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE books ADD COLUMN description VARCHAR(500) DEFAULT '''' COMMENT ''内容简介'' AFTER cover_url',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
