package com.middleberth.payment.dto;

/**
 * From booking-service: "give this customer their money back". payment-service's
 * own copy of the shape; pinned by contracts/refund-request.json.
 */
public record RefundRequest(Long userId,
                            String requestId,
                            String orderId,
                            String paymentId,
                            long amountPaise,
                            String reason) {
}
