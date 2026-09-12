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

    /**
     * A cancellation knows who and which booking, but not which order — booking
     * never stored an order id, and does not need to. payment-service has its own
     * UNIQUE(user_id, request_id) and finds the payment from those two.
     *
     * The amount is left at zero for the same reason: payment-service knows what
     * was actually taken, and that is the number that should go back.
     */
    public static RefundRequest forCancellation(Long userId, String requestId) {
        return new RefundRequest(userId, requestId, null, null, 0, "CANCELLED_BY_PASSENGER");
    }
}
