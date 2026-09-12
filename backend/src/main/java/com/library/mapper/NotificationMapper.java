package com.library.mapper;

import com.library.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NotificationMapper {
    List<Notification> selectByUserId(@Param("userId") Long userId);

    int insert(Notification notification);

    int countUnread(@Param("userId") Long userId);

    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    int markAllRead(@Param("userId") Long userId);

    Notification selectByRef(@Param("refBorrowNo") String refBorrowNo, @Param("eventType") Integer eventType);
}
