-- ===== 修复零日期（'0000-00-00'）导致的 "Zero date value prohibited" =====
-- 背景：批量图书数据里 '2017-05' 等不完整日期被 MySQL 非严格模式转成零日期，
--       JDBC 读取 publish_date 时抛异常。本脚本把零日期统一置为 NULL。
USE library_db;

-- 将零日期/无效日期置为 NULL（正常出版日期远大于 1000 年，安全）
UPDATE books SET publish_date = NULL WHERE publish_date < '1000-01-01';

-- 校验：应为 0
SELECT COUNT(*) AS zero_date_remaining FROM books WHERE publish_date < '1000-01-01';
