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

    /** 按 key 删除设置项（用于旧版配置迁移完成后清理遗留行，避免重复迁移） */
    int deleteByKey(@Param("key") String key);
}
