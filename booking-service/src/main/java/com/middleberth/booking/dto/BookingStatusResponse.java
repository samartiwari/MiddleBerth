package com.middleberth.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * { "status": "PENDING" }
 * { "status": "HELD",       "seat": "B2-31" }
 * { "status": "WAITLISTED", "position": 47 }
 *
 * PENDING is not a BookingStatus — there is no row in the database yet. It only
 * exists at this layer, which is why this is its own type rather than a reuse of
 * BookingResponse.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingStatusResponse(String status, String seat, Integer position) {

    public static BookingStatusResponse pending() {
        return new BookingStatusResponse("PENDING", null, null);
    }

    public static BookingStatusResponse of(BookingResult result) {
        return new BookingStatusResponse(result.status().name(), result.seat(), result.position());
    }
}
