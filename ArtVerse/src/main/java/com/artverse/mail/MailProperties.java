package com.artverse.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "artverse.mail")
public class MailProperties {

    private boolean enabled = false;
    /** 发件人地址，必须与 SMTP 登录账号一致（163/QQ 邮箱强制校验） */
    private String from = "";
    private int codeLength = 6;
    private int codeTtlSeconds = 300;
    private int resendCooldownSeconds = 60;
    private int maxVerifyAttempts = 5;
    private String subjectPrefix = "ArtVerse";
}