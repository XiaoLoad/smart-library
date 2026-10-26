-- V9：智能检索图书向量库
-- 说明：book_embeddings 是唯一数据源（book 元数据拼接文本 + 向量），应用启动时加载到
-- 进程内 InMemoryEmbeddingStore 供语义召回。文本 = 书名 + 作者 + 出版社 + 分类名 + 简介。

CREATE TABLE IF NOT EXISTS book_embeddings (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  book_id     BIGINT      NOT NULL COMMENT '图书 ID（books.id）',
  content     TEXT        NOT NULL COMMENT '参与向量化的拼接文本（书名+作者+出版社+分类+简介）',
  embedding   MEDIUMBLOB  NOT NULL COMMENT 'float32 小端序，512 维 = 2048 字节',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_book (book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
