package com.middleberth.booking.service;

/** What cancelling did, so the caller knows whether to ask for a refund. */
public record CancelOutcome(String pnr, boolean refundDue) {
}
