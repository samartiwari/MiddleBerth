package com.middleberth.booking.payment;

/** What payment-service hands back for an order. Same shape as its CreateOrderResponse. */
public record PaymentOrder(String orderId, long amountPaise, String currency, String keyId) {
}
