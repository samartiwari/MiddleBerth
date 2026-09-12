package com.middleberth.notification.send;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The default: write it down, send nothing. */
@Component
@ConditionalOnProperty(name = "middleberth.mail.mode", havingValue = "log", matchIfMissing = true)
@Slf4j
public class LoggingNotifier implements Notifier {

    private final List<Mail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(Mail mail) {
        sent.add(mail);
        // Masked. Now that these are real passengers rather than a seeded demo row,
        // a full address in the logs is somebody's personal data sitting in a file
        // that gets copied around.
        log.info("MAIL to {} — {}", masked(mail.to()), mail.subject());
    }

    private static String masked(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at <= 0 ? "(hidden)" : address.charAt(0) + "***" + address.substring(at);
    }

    /** Test hook: what would have been sent. */
    public List<Mail> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }
}
