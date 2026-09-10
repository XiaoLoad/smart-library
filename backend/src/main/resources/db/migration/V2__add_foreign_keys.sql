-- ===== V2 外键约束（基线后首个生效迁移） =====
-- 为逻辑关联补上物理外键，保证引用完整性（删用户/图书不会留下孤儿记录）。
-- 现有库经 baseline 跳过 V1 后，本文件会在启动时执行一次；
-- 若数据存在孤儿（如指向不存在 user/book 的 borrow），下面语句会报错，
-- 属预期——请先清理脏数据再启动。全新库由 V1+V2 顺序建立，天然带外键。
-- 注：categories.parent_id 用 0 作根哨兵，0 不是合法 id，故不自引用外键。

-- 借阅记录
ALTER TABLE borrows
  ADD CONSTRAINT fk_borrows_user  FOREIGN KEY (user_id)  REFERENCES users(id),
  ADD CONSTRAINT fk_borrows_book  FOREIGN KEY (book_id)  REFERENCES books(id);

-- 罚款
ALTER TABLE fines
  ADD CONSTRAINT fk_fines_user   FOREIGN KEY (user_id)           REFERENCES users(id),
  ADD CONSTRAINT fk_fines_borrow FOREIGN KEY (borrow_record_id) REFERENCES borrows(id);

-- 图书
ALTER TABLE books
  ADD CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES categories(id);

-- 公告
ALTER TABLE announcements
  ADD CONSTRAINT fk_announcements_publisher FOREIGN KEY (publisher_id) REFERENCES users(id);

-- 通知
ALTER TABLE notifications
  ADD CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id);

-- AI 对话
ALTER TABLE ai_conversations
  ADD CONSTRAINT fk_ai_conv_user FOREIGN KEY (user_id) REFERENCES users(id);

-- AI 消息
ALTER TABLE ai_messages
  ADD CONSTRAINT fk_ai_msgs_conv FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id);
