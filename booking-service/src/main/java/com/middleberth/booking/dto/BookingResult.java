package com.middleberth.booking.dto;

import com.middleberth.booking.domain.BookingStatus;

/** Either they got a berth, or a place in the queue. Never both. */
public record BookingResult(BookingStatus status, String seat, Integer position) {

    public static BookingResult held(String seatLabel) {
        return new BookingResult(BookingStatus.HELD, seatLabel, null);
    }

    public static BookingResult waitlisted(int position) {
        return new BookingResult(BookingStatus.WAITLISTED, null, position);
    }
}
