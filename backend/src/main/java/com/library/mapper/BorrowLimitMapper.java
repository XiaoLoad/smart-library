package com.library.mapper;

import com.library.entity.BorrowLimit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BorrowLimitMapper {
    BorrowLimit selectByType(@Param("userType") Integer userType);
}
