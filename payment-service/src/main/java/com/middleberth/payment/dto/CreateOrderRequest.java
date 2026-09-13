package com.middleberth.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Sent by booking-service when someone clicks Pay Now on a hold.
 *
 * amountPaise is what to charge. refundablePaise is how much of that is the ticket
 * rather than the convenience fee, and it is the most this payment will ever give
 * back to a passenger who cancels. This service does not know how a fare is built
 * up and should not have to — it is told the number.
 */
public record CreateOrderRequest(
        @NotNull @Positive Long userId,
        @NotBlank String requestId,
        @Positive long amountPaise,
        @Positive long refundablePaise) {
}
