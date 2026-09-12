package com.library.controller;

import com.library.common.Result;
import com.library.dto.IdRequest;
import com.library.entity.Notification;
import com.library.mapper.NotificationMapper;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 未读通知数（铃铛角标用）。
     */
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        CurrentUser cu = CurrentUser.get();
        int count = cu == null ? 0 : notificationMapper.countUnread(cu.getUserId());
        return Result.success(Map.of("count", count));
    }

    /**
     * 标记单条已读。
     */
    @PostMapping("/read")
    public Result<Void> read(@RequestBody IdRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (req.getId() != null) {
            notificationMapper.markRead(req.getId(), cu.getUserId());
        }
        return Result.success();
    }

    /**
     * 全部标记为已读。
     */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        CurrentUser cu = CurrentUser.get();
        notificationMapper.markAllRead(cu.getUserId());
        return Result.success("已全部标记为已读", null);
    }
}
