package com.library.mapper;

import com.library.entity.BorrowLimit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BorrowLimitMapper {
    BorrowLimit selectByType(@Param("userType") Integer userType);

    /** 全部身份的借阅额度规则（按 user_type 升序） */
    List<BorrowLimit> selectAll();

    /** 按身份插入或更新额度规则（user_type 唯一键） */
    void upsert(BorrowLimit limit);
}
