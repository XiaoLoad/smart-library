package com.library.service;

import com.library.entity.MailConfig;
import com.library.mapper.MailConfigMapper;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * 邮件发送服务：SMTP 参数动态取自 mail_config 表，便于管理员在系统设置中调整而无需重启。
 * 返回 null 表示发送成功；返回非空字符串表示失败原因。
 */
@Service
public class MailService {

    private final MailConfigMapper mailConfigMapper;

    public MailService(MailConfigMapper mailConfigMapper) {
        this.mailConfigMapper = mailConfigMapper;
    }

    public String send(String to, String subject, String body) {
        MailConfig cfg = mailConfigMapper.selectOne();
        if (cfg == null || cfg.getEnabled() == null || cfg.getEnabled() != 1) {
            return "邮件渠道未启用";
        }
        if (to == null || to.isBlank()) {
            return "收件人邮箱为空";
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(cfg.getSmtpHost());
            sender.setPort(cfg.getSmtpPort());
            sender.setUsername(cfg.getUsername());
            sender.setPassword(cfg.getPassword());
            sender.setDefaultEncoding("UTF-8");

            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            // SMTP 连接/读写均设上限，避免 SMTP 不可达时无限阻塞请求线程
            // （曾导致 /borrow/urge 等接口超过前端超时，催还/重发提示 timeout of 10000ms exceeded）
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.timeout", "8000");
            props.put("mail.smtp.writetimeout", "8000");
            if (cfg.getUseTls() != null && cfg.getUseTls() == 1) {
                // 465/994 为隐式 SSL 端口（连接即 SSL，如 QQ 邮箱），需启用 ssl.enable；
                // 其余端口（如 587/25）走 STARTTLS。旧逻辑只配 STARTTLS，对 465 会连不通。
                int port = cfg.getSmtpPort() == null ? 25 : cfg.getSmtpPort();
                if (port == 465 || port == 994) {
                    props.put("mail.smtp.ssl.enable", "true");
                } else {
                    props.put("mail.smtp.starttls.enable", "true");
                    props.put("mail.smtp.starttls.required", "true");
                }
            }
            sender.setJavaMailProperties(props);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(cfg.getFromAddr());
            helper.setTo(to.trim());
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }
}
