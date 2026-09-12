package com.library.service;

import com.library.entity.NotificationLog;
import com.library.mapper.NotificationLogMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 催还通知定时任务：每日 09:00 扫描在借记录并触发通知。
 * 手动触发入口供管理员在「通知管理」中即时测试。
 * drainPendingEmails 为兜底：捞取卡在 PENDING 超过 2 分钟的邮件重投，避免永久卡死。
 */
@Component
public class NotificationScheduler {

    private final NotificationService notificationService;
    private final NotificationLogMapper logMapper;
    private final AsyncMailDispatcher dispatcher;

    public NotificationScheduler(NotificationService notificationService,
                                NotificationLogMapper logMapper,
                                AsyncMailDispatcher dispatcher) {
        this.notificationService = notificationService;
        this.logMapper = logMapper;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void scheduledScan() {
        notificationService.scanAndNotify();
    }

    public int manualTrigger() {
        return notificationService.scanAndNotify();
    }

    /**
     * 兜底重投：每 60s 捞取卡在 PENDING 超过 2 分钟的邮件重新投递。
     * 仅处理 2 分钟前的记录，与正常异步投递（秒级）错开，避免重复发送；
     * 任何原因（事件丢失、执行器瞬时异常）导致的卡死都能被这里兜底。
     */
    @Scheduled(fixedDelay = 60000)
    public void drainPendingEmails() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        List<NotificationLog> stuck = logMapper.selectStuckPendingEmails(cutoff);
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        List<EmailTask> tasks = stuck.stream()
                .map(l -> new EmailTask(l.getId(), l.getToEmail(), l.getSubject(), l.getContent()))
                .collect(Collectors.toList());
        dispatcher.dispatch(tasks);
    }
}
