package com.library.service;

import com.library.mapper.NotificationLogMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 真正的 SMTP 投递执行器：在独立线程池（taskExecutor）里完成发送并回写状态。
 * 与 EmailDispatchListener 分离，避免把 @Async 直接挂在 @TransactionalEventListener 上——
 * 那种组合会让 Spring 在事务提交后不触发监听器，导致邮件永远卡在 PENDING。
 */
@Component
public class AsyncMailDispatcher {

    private final MailService mailService;
    private final NotificationLogMapper logMapper;

    public AsyncMailDispatcher(MailService mailService, NotificationLogMapper logMapper) {
        this.mailService = mailService;
        this.logMapper = logMapper;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(List<EmailTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (EmailTask t : tasks) {
            // 原子认领：仅当状态仍为 PENDING 时才接手，避免事件链路与兜底定时任务并发
            // 处理同一封邮件导致重复发送
            int claimed = logMapper.claimPending(t.getLogId());
            if (claimed == 0) {
                continue;
            }
            try {
                String err = mailService.send(t.getEmail(), t.getSubject(), t.getBody());
                if (err == null) {
                    logMapper.updateStatus(t.getLogId(), "SENT", null, LocalDateTime.now());
                } else {
                    logMapper.updateStatus(t.getLogId(), "FAILED", err, null);
                }
            } catch (Exception e) {
                // 任何意外异常都不应让状态永久停留在 PENDING
                logMapper.updateStatus(t.getLogId(), "FAILED", e.getMessage(), null);
            }
        }
    }
}
