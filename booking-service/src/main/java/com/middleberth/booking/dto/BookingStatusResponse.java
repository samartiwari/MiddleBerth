package com.middleberth.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;

/**
 * { "status": "PENDING", "retryAfterMs": 500 }
 * { "status": "HELD",       "seat": "B2-31" }
 * { "status": "CONFIRMED",  "seat": "B2-31", "pnr": "4728193056" }
 * { "status": "WAITLIST_HELD", "position": 12, "payBy": "..." }
 * { "status": "REGRETTED" }
 *
 * PENDING is not a BookingStatus — there is no row in the database yet. It only
 * exists at this layer, which is why it is a String here rather than the enum.
 *
 * retryAfterMs comes only with PENDING: how long the page should leave it before
 * asking again, longer the longer the booking has waited. Not the Retry-After
 * header, because that counts whole seconds and the first waits are half a second.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingStatusResponse(String status, String seat, Integer position, Instant payBy,
                                    String pnr, Long retryAfterMs) {

    public static BookingStatusResponse pending(Duration retryAfter) {
        return new BookingStatusResponse("PENDING", null, null, null, null, retryAfter.toMillis());
    }

    public static BookingStatusResponse of(BookingResult result) {
        return new BookingStatusResponse(result.status().name(), result.seat(),
                result.position(), result.payBy(), result.pnr(), null);
    }
}
