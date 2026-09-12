package com.library.mapper;

import com.library.entity.MailConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MailConfigMapper {
    MailConfig selectOne();

    int update(MailConfig config);
}
