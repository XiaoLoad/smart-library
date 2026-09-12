package com.library.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 催还通知定时任务：每日 09:00 扫描在借记录并触发通知。
 * 手动触发入口供管理员在「通知管理」中即时测试。
 */
@Component
public class NotificationScheduler {

    private final NotificationService notificationService;

    public NotificationScheduler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void scheduledScan() {
        notificationService.scanAndNotify();
    }

    public int manualTrigger() {
        return notificationService.scanAndNotify();
    }
}
