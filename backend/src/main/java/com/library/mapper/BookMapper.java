package com.library.mapper;

import com.library.entity.Book;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface BookMapper {

    List<Book> selectList(@Param("keyword") String keyword, @Param("categoryIds") List<Long> categoryIds);

    Book selectById(@Param("id") Long id);

    Book selectByIsbn(@Param("isbn") String isbn);

    void insert(Book book);

    void update(Book book);

    /** 原子扣减库存，仅当 available_count >= count 时生效；返回影响行数（0 表示库存不足） */
    int decreaseAvailable(@Param("id") Long id, @Param("count") int count);

    void increaseAvailable(@Param("id") Long id, @Param("count") int count);

    /** 统计指定分类集合下的图书数量（用于删除分类前校验） */
    int countByCategoryIds(@Param("categoryIds") List<Long> categoryIds);

    /**
     * 按分类分组统计图书数量。
     * 返回每行 Map：{ categoryId: 分类ID, cnt: 图书数量 }（仅含有图书的分类）
     */
    List<Map<String, Object>> countGroupByCategory();
}
