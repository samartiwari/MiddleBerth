package com.middleberth.booking.dto;

import com.middleberth.booking.domain.BookingStatus;

import java.time.Instant;

/**
 * The outcome of a booking at this moment.
 *
 *   HELD            seat + payBy        pay by then or lose it
 *   WAITLIST_HELD   position + payBy    pay by then to keep your place
 *   CONFIRMED       seat
 *   WAITLISTED      position
 *   EXPIRED         —
 *   REGRETTED       —                   waitlist was full
 */
public record BookingResult(BookingStatus status, String seat, Integer position, Instant payBy) {

    public static BookingResult held(String seatLabel, Instant payBy) {
        return new BookingResult(BookingStatus.HELD, seatLabel, null, payBy);
    }

    public static BookingResult waitlistHeld(int position, Instant payBy) {
        return new BookingResult(BookingStatus.WAITLIST_HELD, null, position, payBy);
    }

    public static BookingResult confirmed(String seatLabel) {
        return new BookingResult(BookingStatus.CONFIRMED, seatLabel, null, null);
    }

    public static BookingResult waitlisted(int position) {
        return new BookingResult(BookingStatus.WAITLISTED, null, position, null);
    }

    public static BookingResult expired() {
        return new BookingResult(BookingStatus.EXPIRED, null, null, null);
    }

    public static BookingResult regretted() {
        return new BookingResult(BookingStatus.REGRETTED, null, null, null);
    }
}
