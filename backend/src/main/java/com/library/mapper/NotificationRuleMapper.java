package com.library.mapper;

import com.library.entity.NotificationRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationRuleMapper {
    List<NotificationRule> selectAll();

    NotificationRule selectByEventType(@Param("eventType") String eventType);

    int upsert(NotificationRule rule);
}
