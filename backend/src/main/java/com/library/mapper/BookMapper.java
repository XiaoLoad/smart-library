package com.library.mapper;

import com.library.entity.Book;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BookMapper {

    List<Book> selectList(@Param("keyword") String keyword, @Param("categoryIds") List<Long> categoryIds);

    Book selectById(@Param("id") Long id);

    Book selectByIsbn(@Param("isbn") String isbn);

    void insert(Book book);

    void decreaseAvailable(@Param("id") Long id, @Param("count") int count);

    void increaseAvailable(@Param("id") Long id, @Param("count") int count);
}
