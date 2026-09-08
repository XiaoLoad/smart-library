package com.library.mapper;

import com.library.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CategoryMapper {

    List<Category> selectAll();

    Category selectById(@Param("id") Long id);

    Category selectByCode(@Param("code") String code);

    void insert(Category category);

    void update(Category category);

    void updateStatusBatch(@Param("ids") List<Long> ids, @Param("status") Integer status);
}
