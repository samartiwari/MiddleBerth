package com.middleberth.payment.dto;

import java.time.Instant;

/**
 * "This booking has been paid for." Published on the payment-events topic, and
 * read by booking-service to confirm the hold (phase 5c).
 *
 * paidAt is when the customer paid, from the gateway — not when we heard. A
 * payment made at 4:59 must count as 4:59 even if the webhook arrives at 5:30.
 *
 * Shape is pinned by contracts/payment-event.json, same as the seat-count event.
 */
public record PaymentEvent(String type,
                           Long userId,
                           String requestId,
                           String orderId,
                           String paymentId,
                           long amountPaise,
                           Instant paidAt) {

    public static PaymentEvent paid(Long userId, String requestId, String orderId,
                                    String paymentId, long amountPaise, Instant paidAt) {
        return new PaymentEvent("PAID", userId, requestId, orderId, paymentId, amountPaise, paidAt);
    }
}
