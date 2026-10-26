package com.library.mapper;

import com.library.entity.BookEmbedding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 图书向量 Mapper（book_embeddings）。
 */
@Mapper
public interface BookEmbeddingMapper {

    /** 全部图书向量（含 embedding 与书目展示字段），用于启动时加载内存索引 */
    List<BookEmbedding> selectAllWithEmbedding();

    BookEmbedding selectByBookId(@Param("bookId") Long bookId);

    int insert(BookEmbedding embedding);

    int updateByBookId(BookEmbedding embedding);

    int deleteByBookId(@Param("bookId") Long bookId);

    int deleteAll();

    int count();
}
