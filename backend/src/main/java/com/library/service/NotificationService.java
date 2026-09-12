package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.entity.Borrow;
import com.library.entity.Notification;
import com.library.entity.NotificationLog;
import com.library.entity.NotificationRule;
import com.library.entity.User;
import com.library.mapper.BorrowMapper;
import com.library.mapper.NotificationLogMapper;
import com.library.mapper.NotificationMapper;
import com.library.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 催还通知编排：扫描在借记录 → 按规则触发事件 → 去重 → 分发站内信与邮件 → 回写发送日志。
 */
@Service
public class NotificationService {

    private final NotificationRuleService ruleService;
    private final BorrowMapper borrowMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final MailService mailService;

    public NotificationService(NotificationRuleService ruleService, BorrowMapper borrowMapper,
                              NotificationLogMapper logMapper, NotificationMapper notificationMapper,
                              UserMapper userMapper, MailService mailService) {
        this.ruleService = ruleService;
        this.borrowMapper = borrowMapper;
        this.logMapper = logMapper;
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
        this.mailService = mailService;
    }

    @Transactional
    public int scanAndNotify() {
        List<NotificationRule> rules = ruleService.listEnabled();
        if (rules.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        int dispatched = 0;
        for (NotificationRule rule : rules) {
            int offset = rule.getOffsetDays() == null ? 0 : rule.getOffsetDays();
            LocalDate target = today.plusDays(offset);
            List<Borrow> borrows = borrowMapper.selectDueOn(1, target.atStartOfDay(), target.atTime(23, 59, 59));
            for (Borrow b : borrows) {
                for (String channel : parseChannels(rule.getChannel())) {
                    dispatched += dispatch(b, rule, channel);
                }
            }
        }
        return dispatched;
    }

    private int dispatch(Borrow b, NotificationRule rule, String channel) {
        NotificationLog existing = logMapper.selectByDedup(b.getBorrowNo(), rule.getEventType(), channel);
        if (existing != null && "SENT".equals(existing.getStatus())) {
            return 0;
        }
        String readerName = b.getUserName();
        String bookTitle = b.getBookTitle();
        String dueDate = b.getDueTime() == null ? "" : b.getDueTime().toLocalDate().toString();
        int daysOverdue = rule.getOffsetDays() == null ? 0 : Math.max(rule.getOffsetDays(), 0);
        String subject = render(rule.getSubjectTpl(), readerName, bookTitle, dueDate, b.getBorrowNo(), daysOverdue);
        String body = render(rule.getBodyTpl(), readerName, bookTitle, dueDate, b.getBorrowNo(), daysOverdue);

        NotificationLog log = new NotificationLog();
        log.setBorrowNo(b.getBorrowNo());
        log.setUserId(b.getUserId());
        log.setEventType(rule.getEventType());
        log.setChannel(channel);
        log.setRuleId(rule.getId());
        log.setSubject(subject);
        log.setContent(body);
        log.setStatus("PENDING");

        if ("INAPP".equals(channel)) {
            Notification n = new Notification();
            n.setUserId(b.getUserId());
            n.setType("DUE_SOON".equals(rule.getEventType()) ? 4 : 2);
            n.setTitle(subject);
            n.setContent(body);
            n.setIsRead(0);
            n.setEventType("DUE_SOON".equals(rule.getEventType()) ? 1 : 2);
            n.setRefBorrowNo(b.getBorrowNo());
            notificationMapper.insert(n);
            log.setStatus("SENT");
            log.setSentAt(LocalDateTime.now());
        } else {
            User u = userMapper.selectById(b.getUserId());
            String email = u == null ? null : u.getEmail();
            if (email == null || email.isBlank()) {
                log.setStatus("SKIPPED");
                log.setErrorMsg("读者邮箱为空");
            } else {
                log.setToEmail(email);
                String err = mailService.send(email, subject, body);
                if (err == null) {
                    log.setStatus("SENT");
                    log.setSentAt(LocalDateTime.now());
                } else {
                    log.setStatus("FAILED");
                    log.setErrorMsg(err);
                }
            }
        }
        if (existing == null) {
            logMapper.insert(log);
        } else {
            logMapper.updateStatus(existing.getId(), log.getStatus(), log.getErrorMsg(), log.getSentAt());
        }
        return 1;
    }

    /**
     * 重发单条通知（邮件发送失败或站内未送达时人工补救）。
     * 站内信：若对应通知已存在则视为已送达，否则补建一条未读通知。
     * 邮件：用日志中保存的主题/正文重投，收件人缺失时回查读者邮箱。
     */
    @Transactional
    public String resend(Long logId) {
        NotificationLog log = logMapper.selectById(logId);
        if (log == null) {
            throw new BusinessException("通知记录不存在");
        }
        if ("INAPP".equals(log.getChannel())) {
            int eventTypeInt = log.getEventType() != null && log.getEventType().startsWith("DUE_SOON") ? 1 : 2;
            Notification existing = notificationMapper.selectByRef(log.getBorrowNo(), eventTypeInt);
            if (existing == null) {
                Notification n = new Notification();
                n.setUserId(log.getUserId());
                n.setType(eventTypeInt == 1 ? 4 : 2);
                n.setTitle(log.getSubject());
                n.setContent(log.getContent());
                n.setIsRead(0);
                n.setEventType(eventTypeInt);
                n.setRefBorrowNo(log.getBorrowNo());
                notificationMapper.insert(n);
            }
            logMapper.updateStatus(logId, "SENT", null, LocalDateTime.now());
            return "站内通知已重新推送";
        }
        String email = log.getToEmail();
        if (email == null || email.isBlank()) {
            User u = userMapper.selectById(log.getUserId());
            if (u == null || u.getEmail() == null || u.getEmail().isBlank()) {
                logMapper.updateStatus(logId, "SKIPPED", "读者邮箱为空", null);
                return "读者邮箱为空，无法重发";
            }
            email = u.getEmail();
        }
        String err = mailService.send(email, log.getSubject(), log.getContent());
        if (err == null) {
            logMapper.updateStatus(logId, "SENT", null, LocalDateTime.now());
            return "邮件已重新发送";
        }
        logMapper.updateStatus(logId, "FAILED", err, null);
        return "重发失败：" + err;
    }

    private List<String> parseChannels(String channel) {
        if ("BOTH".equals(channel)) {
            List<String> both = new ArrayList<>();
            both.add("INAPP");
            both.add("EMAIL");
            return both;
        }
        List<String> one = new ArrayList<>();
        one.add(channel);
        return one;
    }

    private String render(String tpl, String readerName, String bookTitle, String dueDate,
                          String borrowNo, int daysOverdue) {
        if (tpl == null) return "";
        return tpl
                .replace("{readerName}", readerName == null ? "" : readerName)
                .replace("{bookTitle}", bookTitle == null ? "" : bookTitle)
                .replace("{dueDate}", dueDate == null ? "" : dueDate)
                .replace("{borrowNo}", borrowNo == null ? "" : borrowNo)
                .replace("{daysOverdue}", String.valueOf(daysOverdue));
    }
}
