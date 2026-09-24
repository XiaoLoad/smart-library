-- V7：RAG 知识库（文档 + 切片）
-- 说明：rag_segments 是唯一数据源（含文本 + 向量），应用启动时加载到进程内 InMemoryEmbeddingStore 供检索。

-- 知识库文档
CREATE TABLE IF NOT EXISTS rag_documents (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  title         VARCHAR(255) NOT NULL COMMENT '文档标题',
  source_type   VARCHAR(32)  NOT NULL DEFAULT 'manual' COMMENT 'manual=人工上传, book=书目同步（预留）',
  source_ref    VARCHAR(64)  DEFAULT NULL COMMENT '来源引用（如 book.id，预留）',
  scope         VARCHAR(16)  NOT NULL DEFAULT 'public' COMMENT 'public=读者可见, admin=仅管理员',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用, 0=停用',
  segment_count INT          NOT NULL DEFAULT 0 COMMENT '片段数（冗余，便于列表展示）',
  raw_text      MEDIUMTEXT   NULL COMMENT '解析后的原文，用于按最新 chunkSize 重建索引',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_scope_status (scope, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 切片（含向量）
CREATE TABLE IF NOT EXISTS rag_segments (
  id          BIGINT     NOT NULL AUTO_INCREMENT,
  document_id BIGINT     NOT NULL,
  seq         INT        NOT NULL COMMENT '片段序号，从 0 开始',
  content     TEXT       NOT NULL COMMENT '片段原文（用于展示引用来源）',
  embedding   MEDIUMBLOB NOT NULL COMMENT 'float32 小端序，512 维 = 2048 字节',
  create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;