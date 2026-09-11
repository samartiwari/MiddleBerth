package com.middleberth.payment.dto;

/**
 * Everything the browser needs to open Razorpay's checkout page. keyId is the
 * PUBLIC key — safe to send to a browser. The secret never leaves the server.
 */
public record CreateOrderResponse(String orderId, long amountPaise, String currency, String keyId) {
}
