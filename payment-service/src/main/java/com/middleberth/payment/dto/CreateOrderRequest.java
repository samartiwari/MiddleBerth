package com.middleberth.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Sent by booking-service when someone clicks Pay Now on a hold. */
public record CreateOrderRequest(
        @NotNull @Positive Long userId,
        @NotBlank String requestId,
        @Positive long amountPaise) {
}
