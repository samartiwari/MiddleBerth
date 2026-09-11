package com.middleberth.payment.service;

/**
 * A refund was asked for, but this service has not recorded the payment as PAID
 * yet. Thrown on purpose so the request is retried a moment later.
 *
 * It can happen: "paid" is announced inside the transaction that marks it PAID,
 * so for a few milliseconds booking-service can know before this service's own
 * commit lands. Skipping the refund here would lose it; retrying is always right.
 */
public class NotCapturedYetException extends RuntimeException {

    public NotCapturedYetException(String orderId) {
        super("Payment for " + orderId + " is not recorded as PAID yet");
    }
}
