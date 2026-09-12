package com.library.mapper;

import com.library.entity.NotificationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationLogMapper {
    int insert(NotificationLog log);

    NotificationLog selectByDedup(@Param("borrowNo") String borrowNo,
                                  @Param("eventType") String eventType,
                                  @Param("channel") String channel);

    List<NotificationLog> selectAdminPage(@Param("status") String status,
                                         @Param("channel") String channel,
                                         @Param("eventType") String eventType,
                                         @Param("startDate") String startDate,
                                         @Param("endDate") String endDate);

    NotificationLog selectById(@Param("id") Long id);

    List<NotificationLog> selectStuckPendingEmails(@Param("before") java.time.LocalDateTime before);

    /**
     * 原子认领：仅当状态仍为 PENDING 时才置为 SENDING，返回受影响行数。
     * 用于避免事件链路与兜底定时任务并发处理同一封邮件导致重复发送。
     */
    int claimPending(@Param("id") Long id);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMsg") String errorMsg,
                     @Param("sentAt") java.time.LocalDateTime sentAt);
}
