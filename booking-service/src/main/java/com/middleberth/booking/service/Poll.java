package com.middleberth.booking.service;

import com.middleberth.booking.dto.BookingResult;

import java.time.Duration;

/** What a waiting page is told: the answer, or "not yet, ask again in this long". */
public record Poll(BookingResult result, Duration retryAfter) {

    static Poll answered(BookingResult result) {
        return new Poll(result, null);
    }

    static Poll pending(Duration retryAfter) {
        return new Poll(null, retryAfter);
    }
}
