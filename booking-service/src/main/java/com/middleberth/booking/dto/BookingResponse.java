package com.middleberth.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.middleberth.booking.domain.BookingStatus;

/**
 * { "status": "HELD",       "seat": "B2-31" }
 * { "status": "WAITLISTED", "position": 47 }
 *
 * NON_NULL so the irrelevant half of the pair is left out rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingResponse(BookingStatus status, String seat, Integer position) {

    public static BookingResponse from(BookingResult result) {
        return new BookingResponse(result.status(), result.seat(), result.position());
    }
}
