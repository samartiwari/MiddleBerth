package com.middleberth.booking.dto;

import java.time.Instant;

/**
 * What the browser needs to open Razorpay's checkout page, plus the deadline.
 * keyId is the public key — safe to hand to a browser.
 *
 * The amount is broken out rather than sent as one number, because a customer
 * being charged more than the fare is entitled to see why — and because the two
 * halves behave differently if they later cancel: the base fare comes back, the
 * convenience fee does not.
 *
 *   amountPaise = baseFarePaise + convenienceFeePaise
 */
public record PayNowResponse(String orderId, long amountPaise, String currency, String keyId,
                             long baseFarePaise, long convenienceFeePaise, Instant payBy) {
}
