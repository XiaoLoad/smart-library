package com.library.mapper;

import com.library.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NotificationMapper {
    List<Notification> selectByUserId(@Param("userId") Long userId);
}
