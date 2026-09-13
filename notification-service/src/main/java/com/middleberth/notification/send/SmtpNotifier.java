package com.middleberth.notification.send;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class SmtpNotifier implements Notifier {

    private final JavaMailSender mailSender;

    @Value("${middleberth.mail.from}")
    private String from;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    /**
     * Fail at startup, not at the first ticket — the same rule the live payment
     * gateway follows.
     *
     * Without this, MAIL_MODE=smtp with no credentials comes up perfectly healthy
     * and then fails every single mail. Each one retries, then parks on a dead
     * letter topic, and the first anyone knows of it is a passenger who never got
     * their ticket.
     */
    @PostConstruct
    void checkConfigured() {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("MAIL_MODE=smtp needs MAIL_USERNAME and MAIL_PASSWORD "
                    + "in the environment");
        }
        if (!from.equalsIgnoreCase(username)) {
            // Gmail will not send as an address you do not own; it rewrites the
            // sender or refuses outright. Better to say so now than to wonder later
            // why every ticket arrives from somebody else.
            log.warn("middleberth.mail.from ({}) is not the account being logged in as ({}). "
                    + "Most providers will rewrite or reject that.", from, username);
        }
        log.info("SMTP mail enabled, sending as {}", from);
    }

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
