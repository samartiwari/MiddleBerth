package com.middleberth.notification.send;

/**
 * Sending, and nothing else.
 *
 * Two implementations, chosen by middleberth.mail.mode — the same arrangement as
 * the stub Razorpay gateway. Load tests must never send real mail: we would be
 * measuring a mail server, and a few lakh strangers would get a ticket.
 */
public interface Notifier {

    /** Throwing means "could not send" — the listener will try again. */
    void send(Mail mail);
}
