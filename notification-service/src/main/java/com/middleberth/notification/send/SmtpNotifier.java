package com.middleberth.notification.send;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The real thing, for the deployed system. Turned on with MAIL_MODE=smtp, and the
 * server and credentials come from spring.mail.* in the environment — never from
 * a file in this repository.
 */
@Component
@ConditionalOnProperty(name = "middleberth.mail.mode", havingValue = "smtp")
@RequiredArgsConstructor
public class SmtpNotifier implements Notifier {

    private final JavaMailSender mailSender;

    @Value("${middleberth.mail.from}")
    private String from;

    @Override
    public void send(Mail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        mailSender.send(message);          // throws on failure, so the listener retries
    }
}
