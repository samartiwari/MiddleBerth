package com.middleberth.booking.dto;

import java.time.Instant;

/**
 * What payment-service publishes on payment-events when a booking is paid for.
 *
 * booking-service's own copy of the record — the services share a message shape,
 * not a class. The shape is pinned by contracts/payment-event.json, which both
 * sides' tests read.
 */
public record PaymentEvent(String type,
                           Long userId,
                           String requestId,
                           String orderId,
                           String paymentId,
                           long amountPaise,
                           Instant paidAt) {
}
