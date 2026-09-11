package com.middleberth.booking.dto;

import java.time.Instant;

/**
 * What the browser needs to open Razorpay's checkout page, plus the deadline.
 * keyId is the public key — safe to hand to a browser.
 */
public record PayNowResponse(String orderId, long amountPaise, String currency, String keyId, Instant payBy) {
}
