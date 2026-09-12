package com.library.service;

/**
 * 单条待投递邮件的上下文：关联通知日志 ID，便于后台投递完成后回写发送状态。
 */
public class EmailTask {
    private final Long logId;
    private final String email;
    private final String subject;
    private final String body;

    public EmailTask(Long logId, String email, String subject, String body) {
        this.logId = logId;
        this.email = email;
        this.subject = subject;
        this.body = body;
    }

    public Long getLogId() {
        return logId;
    }

    public String getEmail() {
        return email;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}
