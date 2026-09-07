package com.library.controller;

import com.library.common.Result;
import com.library.entity.Notification;
import com.library.mapper.NotificationMapper;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知控制器
 */
@RestController
@RequestMapping("/notification")
public class NotificationController {

    @Autowired
    private NotificationMapper notificationMapper;

    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        CurrentUser cu = CurrentUser.get();
        List<Notification> list = notificationMapper.selectByUserId(cu.getUserId());
        return Result.success(Map.of("list", list, "total", list.size()));
    }
}
