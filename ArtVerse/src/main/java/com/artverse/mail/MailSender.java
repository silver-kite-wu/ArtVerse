package com.artverse.mail;

public interface MailSender {

    void send(String to, String subject, String html);
}