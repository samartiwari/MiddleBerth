package com.middleberth.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * { "status": "PENDING" }
 * { "status": "HELD",       "seat": "B2-31" }
 * { "status": "WAITLIST_HELD", "position": 12, "payBy": "..." }
 * { "status": "REGRETTED" }
 *
 * PENDING is not a BookingStatus — there is no row in the database yet. It only
 * exists at this layer, which is why this is its own type rather than a reuse of
 * BookingResponse.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingStatusResponse(String status, String seat, Integer position, Instant payBy) {

    public static BookingStatusResponse pending() {
        return new BookingStatusResponse("PENDING", null, null, null);
    }

    public static BookingStatusResponse of(BookingResult result) {
        return new BookingStatusResponse(result.status().name(), result.seat(),
                result.position(), result.payBy());
    }
}
