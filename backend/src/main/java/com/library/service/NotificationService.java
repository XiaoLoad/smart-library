package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.entity.Borrow;
import com.library.entity.Notification;
import com.library.entity.NotificationLog;
import com.library.entity.NotificationRule;
import com.library.dto.UrgeResultVo;
import com.library.entity.User;
import com.library.mapper.BorrowMapper;
import com.library.mapper.NotificationLogMapper;
import com.library.mapper.NotificationMapper;
import com.library.mapper.UserMapper;
import com.library.security.CurrentUser;
import com.library.service.AsyncMailDispatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 催还通知编排：扫描在借记录 → 按规则触发事件 → 去重 → 分发站内信与邮件 → 回写发送日志。
 * 邮件发送已异步化：HTTP 请求线程只负责落库（站内信即时、邮件置为待发送），
 * 真正的 SMTP 投递在事务提交后由 EmailDispatchListener 在独立线程完成，前端不再被 SMTP 阻塞。
 */
@Service
public class NotificationService {

    private final NotificationRuleService ruleService;
    private final BorrowMapper borrowMapper;
    private final NotificationLogMapper logMapper;
    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final MailService mailService;
    private final AsyncMailDispatcher asyncMailDispatcher;

    public NotificationService(NotificationRuleService ruleService, BorrowMapper borrowMapper,
                              NotificationLogMapper logMapper, NotificationMapper notificationMapper,
                              UserMapper userMapper, MailService mailService,
                              AsyncMailDispatcher asyncMailDispatcher) {
        this.ruleService = ruleService;
        this.borrowMapper = borrowMapper;
        this.logMapper = logMapper;
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
        this.mailService = mailService;
        this.asyncMailDispatcher = asyncMailDispatcher;
    }

    @Transactional
    public int scanAndNotify() {
        List<NotificationRule> rules = ruleService.listEnabled();
        if (rules.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        int dispatched = 0;
        List<EmailTask> emailTasks = new ArrayList<>();
        for (NotificationRule rule : rules) {
            int offset = rule.getOffsetDays() == null ? 0 : rule.getOffsetDays();
            LocalDate target = today.plusDays(offset);
            List<Borrow> borrows = borrowMapper.selectDueOn(1, target.atStartOfDay(), target.atTime(23, 59, 59));
            for (Borrow b : borrows) {
                for (String channel : parseChannels(rule.getChannel())) {
                    dispatched += dispatch(b, rule, channel, emailTasks);
                }
            }
        }
        dispatchAfterCommit(emailTasks);
        return dispatched;
    }

    private int dispatch(Borrow b, NotificationRule rule, String channel, List<EmailTask> emailTasks) {
        NotificationLog existing = logMapper.selectByDedup(b.getBorrowNo(), rule.getEventType(), channel);
        if (existing != null && "SENT".equals(existing.getStatus())) {
            return 0;
        }
        String readerName = b.getUserName();
        String bookTitle = b.getBookTitle();
        String dueDate = b.getDueTime() == null ? "" : b.getDueTime().toLocalDate().toString();
        // DUE_SOON 用绝对值表示"距到期天数"，避免模板里出现负数；逾期场景本就为正数
        int rawOffset = rule.getOffsetDays() == null ? 0 : rule.getOffsetDays();
        int daysOverdue = "DUE_SOON".equals(rule.getEventType()) ? Math.abs(rawOffset) : Math.max(rawOffset, 0);
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
            if (existing == null) {
                logMapper.insert(log);
            } else {
                logMapper.updateStatus(existing.getId(), "SENT", null, log.getSentAt());
            }
        } else {
            User u = userMapper.selectById(b.getUserId());
            String email = u == null ? null : u.getEmail();
            if (email == null || email.isBlank()) {
                log.setStatus("SKIPPED");
                log.setErrorMsg("读者邮箱为空");
                if (existing == null) {
                    logMapper.insert(log);
                } else {
                    logMapper.updateStatus(existing.getId(), "SKIPPED", "读者邮箱为空", existing.getSentAt());
                }
            } else {
                log.setToEmail(email);
                Long logId;
                if (existing == null) {
                    logMapper.insert(log);
                    logId = log.getId();
                } else {
                    logId = existing.getId();
                    // 重投：先回置为待发送，由后台异步投递后回写最终结果
                    logMapper.updateStatus(logId, "PENDING", null, null);
                }
                emailTasks.add(new EmailTask(logId, email, subject, body));
            }
        }
        return 1;
    }

    /**
     * 重发单条通知（邮件发送失败或站内未送达时人工补救）。
     * 站内信：若对应通知已存在则视为已送达，否则补建一条未读通知。
     * 邮件：将日志置为待发送并交后台异步重投，调用方立即得到"正在重发"反馈。
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
        // 异步重投：先置为待发送，后台投递完成后回写 SENT / FAILED
        logMapper.updateStatus(logId, "PENDING", null, null);
        dispatchAfterCommit(Collections.singletonList(
                new EmailTask(logId, email, log.getSubject(), log.getContent())));
        return "邮件正在重新发送，请稍后刷新查看结果";
    }

    /**
     * 手动催还（管理员入口）：对逾期记录下发催还通知。
     * 复用站内信 + 邮件分发与写日志能力，但事件类型用独立的 URGE_RETURN，
     * 不与自动逾期通知（OVERDUE_D*）去重，支持管理员重复催还。
     */
    public UrgeResultVo urgeReturn(List<String> borrowNos) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可催还");
        }
        return urgeReturn(borrowNos, cu.getUserId());
    }

    /**
     * 手动催还（显式管理员 ID，供 AI 工具在无 ThreadLocal 的异步线程中调用）。
     * 调用方需自行保证操作者为管理员。
     */
    @Transactional
    public UrgeResultVo urgeReturn(List<String> borrowNos, Long adminId) {
        List<String> nos = borrowNos == null ? Collections.emptyList() : borrowNos;
        List<Map<String, Object>> results = new ArrayList<>(nos.size());
        int success = 0;
        int skipped = 0;
        LocalDate today = LocalDate.now();
        // 催还文案：正式、规范的公文式措辞，含称呼、书名号、借阅号、逾期天数、归还指引与落款
        String subjectTpl = "【图书催还通知】请尽快归还逾期图书";
        String bodyTpl = "尊敬的{readerName}读者：\n"
                + "　　您借阅的《{bookTitle}》（借阅号：{borrowNo}）已于 {dueDate} 到期，截至今日已逾期 {daysOverdue} 天，至今尚未归还。\n"
                + "　　管理员已就此发起催还，请尽快携带图书至图书馆服务台办理归还手续。逾期归还将按馆内规定计收滞纳金；长期逾期将影响您的后续借阅权限。\n"
                + "　　如有疑问，请联系图书馆服务台。感谢您的配合。";
        List<EmailTask> emailTasks = new ArrayList<>();
        for (String no : nos) {
            Map<String, Object> r = new HashMap<>();
            r.put("borrowNo", no);
            try {
                Borrow b = borrowMapper.selectByBorrowNo(no);
                if (b == null) {
                    throw new BusinessException("借阅记录不存在");
                }
                if (b.getStatus() != 3) {
                    r.put("success", false);
                    r.put("message", "仅逾期记录可催还");
                    skipped++;
                    results.add(r);
                    continue;
                }
                int daysOverdue = b.getDueTime() == null ? 0
                        : (int) ChronoUnit.DAYS.between(b.getDueTime().toLocalDate(), today);
                String readerName = b.getUserName();
                String bookTitle = b.getBookTitle();
                String dueDate = b.getDueTime() == null ? "" : b.getDueTime().toLocalDate().toString();
                String subject = render(subjectTpl, readerName, bookTitle, dueDate, b.getBorrowNo(), daysOverdue);
                String body = render(bodyTpl, readerName, bookTitle, dueDate, b.getBorrowNo(), daysOverdue);
                for (String channel : parseChannels("BOTH")) {
                    sendUrge(b, "URGE_RETURN", channel, subject, body, emailTasks);
                }
                r.put("success", true);
                r.put("bookTitle", bookTitle);
                r.put("userName", readerName);
                success++;
            } catch (BusinessException e) {
                r.put("success", false);
                r.put("message", e.getMessage());
                skipped++;
            }
            results.add(r);
        }
        dispatchAfterCommit(emailTasks);
        return new UrgeResultVo(results, success, skipped);
    }

    /**
     * 下发单条催还通知（站内信 + 邮件），按 URGE_RETURN 事件独立记录。
     * 复用 dispatch 的去重思路：notification_log 有唯一键 (borrow_no, event_type, channel)，
     * 故重催时更新原日志而非重复插入，避免唯一键冲突；站内信每次新插入一条（无唯一约束）。
     * 邮件统一置为待发送并交后台异步投递，HTTP 线程无需等待 SMTP。
     */
    private void sendUrge(Borrow b, String eventType, String channel, String subject, String body,
                          List<EmailTask> emailTasks) {
        NotificationLog existing = logMapper.selectByDedup(b.getBorrowNo(), eventType, channel);
        NotificationLog log = new NotificationLog();
        log.setBorrowNo(b.getBorrowNo());
        log.setUserId(b.getUserId());
        log.setEventType(eventType);
        log.setChannel(channel);
        log.setSubject(subject);
        log.setContent(body);
        log.setStatus("SENT");
        log.setSentAt(LocalDateTime.now());
        if ("INAPP".equals(channel)) {
            Notification n = new Notification();
            n.setUserId(b.getUserId());
            n.setType(2);
            n.setTitle(subject);
            n.setContent(body);
            n.setIsRead(0);
            n.setEventType(2);
            n.setRefBorrowNo(b.getBorrowNo());
            notificationMapper.insert(n);
            if (existing == null) {
                logMapper.insert(log);
            } else {
                logMapper.updateStatus(existing.getId(), "SENT", null, log.getSentAt());
            }
        } else {
            User u = userMapper.selectById(b.getUserId());
            String email = u == null ? null : u.getEmail();
            if (email == null || email.isBlank()) {
                log.setStatus("SKIPPED");
                log.setErrorMsg("读者邮箱为空");
                if (existing == null) {
                    logMapper.insert(log);
                } else {
                    logMapper.updateStatus(existing.getId(), "SKIPPED", "读者邮箱为空", existing.getSentAt());
                }
            } else {
                log.setToEmail(email);
                log.setStatus("PENDING");
                log.setSentAt(null);
                Long logId;
                if (existing == null) {
                    logMapper.insert(log);
                    logId = log.getId();
                } else {
                    logId = existing.getId();
                    logMapper.updateStatus(logId, "PENDING", null, null);
                }
                emailTasks.add(new EmailTask(logId, email, subject, body));
            }
        }
    }

    /**
     * 事务提交后异步投递邮件。在 @Transactional 方法内注册 afterCommit 回调，
     * 由独立线程池（taskExecutor）真正发送并回写状态。
     * 相比 @TransactionalEventListener，这种直接注册同步回调的方式不依赖事件适配器的代理机制，
     * 必然在提交后触发、不会静默丢弃事件（此前事件方案在本环境不触发，导致邮件永久卡在 PENDING）。
     */
    private void dispatchAfterCommit(List<EmailTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        final List<EmailTask> toSend = new ArrayList<>(tasks);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncMailDispatcher.dispatch(toSend);
                }
                @Override
                public void flush() {
                }
            });
        } else {
            // 不在事务中（兜底）：直接派发，尽量投递
            asyncMailDispatcher.dispatch(toSend);
        }
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
