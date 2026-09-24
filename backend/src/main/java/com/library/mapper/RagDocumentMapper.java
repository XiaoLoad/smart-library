package com.library.mapper;

import com.library.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 知识库文档 Mapper。
 */
@Mapper
public interface RagDocumentMapper {

    /** 分页列表（不含 raw_text，避免大字段拖慢列表） */
    List<RagDocument> selectPage(@Param("keyword") String keyword, @Param("scope") String scope);

    /** 详情（含 raw_text，用于单篇重建索引） */
    RagDocument selectById(@Param("id") Long id);

    /** 全部启用中的文档（含 raw_text），用于全量重建索引 */
    List<RagDocument> selectEnabled();

    int insert(RagDocument doc);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updateSegmentCount(@Param("id") Long id, @Param("segmentCount") Integer segmentCount);

    int deleteById(@Param("id") Long id);
}