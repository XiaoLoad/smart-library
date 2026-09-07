package com.library.mapper;

import com.library.entity.SystemSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 系统设置 Mapper
 */
@Mapper
public interface SystemSettingMapper {

    SystemSetting selectByKey(@Param("key") String key);

    void upsert(@Param("key") String key, @Param("value") String value);
}
