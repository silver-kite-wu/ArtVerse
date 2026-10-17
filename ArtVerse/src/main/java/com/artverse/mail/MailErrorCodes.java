package com.artverse.mail;

public final class MailErrorCodes {

    public static final int MAIL_DISABLED = 50302;
    public static final int EMAIL_CODE_INVALID = 40001;
    public static final int EMAIL_CODE_EXPIRED = 40002;
    public static final int EMAIL_CODE_TOO_MANY_ATTEMPTS = 40003;
    public static final int RESET_TICKET_INVALID = 40004;
    public static final int EMAIL_NOT_REGISTERED = 40404;
    public static final int EMAIL_SEND_FAILED = 50201;

    private MailErrorCodes() {
    }
}