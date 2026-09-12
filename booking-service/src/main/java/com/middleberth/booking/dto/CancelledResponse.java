package com.middleberth.booking.dto;

/**
 * { "pnr": "4728193056", "refundOnItsWay": true }
 *
 * refundOnItsWay is false when nothing was ever paid — an unpaid hold owes
 * nobody anything.
 */
public record CancelledResponse(String pnr, boolean refundOnItsWay) {
}
