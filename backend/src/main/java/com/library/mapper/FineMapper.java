package com.library.mapper;

import com.library.entity.Fine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FineMapper {
    List<Fine> selectList(@Param("userId") Long userId, @Param("isAdmin") boolean isAdmin);
    Fine selectById(@Param("id") Long id);
    void updatePaid(@Param("id") Long id, @Param("paidTime") LocalDateTime paidTime, @Param("status") Integer status);
}
