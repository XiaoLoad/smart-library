package com.library.mapper;

import com.library.entity.RagSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 知识库切片 Mapper。
 */
@Mapper
public interface RagSegmentMapper {

    /** 某文档的片段明细（不含 embedding） */
    List<RagSegment> listByDocumentId(@Param("documentId") Long documentId);

    /**
     * 全部启用中文档的片段（含 embedding 与所属文档标题/范围），
     * 用于应用启动时把全量索引加载进内存。
     */
    List<RagSegment> selectAllEnabledWithEmbedding();

    /**
     * 某文档的全部片段（含 embedding，不看文档启用状态），
     * 用于文档重新启用时按原向量加回内存索引，免重新切分与嵌入。
     */
    List<RagSegment> selectByDocumentIdWithEmbedding(@Param("documentId") Long documentId);

    int insert(RagSegment segment);

    int deleteByDocumentId(@Param("documentId") Long documentId);

    int deleteAll();

    int countByDocumentId(@Param("documentId") Long documentId);
}