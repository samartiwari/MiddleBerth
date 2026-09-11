package com.middleberth.payment.gateway;

/** A payment the gateway says was captured on an order. createdAt is unix seconds, as Razorpay sends it. */
public record CapturedPayment(String paymentId, long amountPaise, long createdAt) {
}
