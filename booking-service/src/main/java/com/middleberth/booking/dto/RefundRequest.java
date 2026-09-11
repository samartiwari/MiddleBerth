package com.middleberth.booking.dto;

/**
 * "Give this customer their money back." Published on refund-requests, carried out
 * by payment-service — the only service that talks to the gateway.
 *
 * Asynchronous on purpose: nobody is waiting on a refund, so it goes over Kafka
 * rather than adding a second synchronous call between services.
 *
 * Shape is pinned by contracts/refund-request.json.
 */
public record RefundRequest(Long userId,
                            String requestId,
                            String orderId,
                            String paymentId,
                            long amountPaise,
                            String reason) {
}
