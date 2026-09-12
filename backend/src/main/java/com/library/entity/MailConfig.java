package com.library.entity;

import lombok.Data;

@Data
public class MailConfig {
    private Long id;
    private String smtpHost;
    private Integer smtpPort;
    private String username;
    private String password;
    private String fromAddr;
    private Integer useTls;
    private Integer enabled;
}
