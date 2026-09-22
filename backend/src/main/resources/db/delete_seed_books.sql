-- ===== 删除 10 本种子图书（红楼梦/百年孤独/史记…）=====
-- 库名：library_db
-- 说明：种子借阅/罚款记录通过外键引用这些书，须先清理子表；
--       删除条件带「无封面」双保险，确保绝不误删 452 本批量图书。
USE library_db;

-- 1) 先预览将删除的目标（应恰好 10 行：红楼梦、百年孤独、史记…）
SELECT id, title, isbn FROM books
WHERE id <= 10 AND (cover_url = '' OR cover_url IS NULL);

-- 2) 清理引用它们的借阅/罚款记录（外键约束要求先删子表）
DELETE FROM fines
WHERE borrow_record_id IN (
  SELECT id FROM borrows
  WHERE book_id IN (SELECT id FROM books WHERE id <= 10 AND (cover_url = '' OR cover_url IS NULL))
);
DELETE FROM borrows
WHERE book_id IN (SELECT id FROM books WHERE id <= 10 AND (cover_url = '' OR cover_url IS NULL));

-- 3) 删除种子图书本身
DELETE FROM books
WHERE id <= 10 AND (cover_url = '' OR cover_url IS NULL);

-- 4) 校验：剩余应为 452 本（全部带封面）
SELECT COUNT(*) AS remaining FROM books;
