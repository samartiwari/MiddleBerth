package com.middleberth.booking.dto;

import com.middleberth.booking.domain.BookingStatus;

import java.time.Instant;

/**
 * The outcome of a booking at this moment.
 *
 *   HELD            seat + payBy        pay by then or lose it
 *   WAITLIST_HELD   position + payBy    pay by then to keep your place
 *   CONFIRMED       seat + pnr
 *   WAITLISTED      position + pnr      a waitlisted ticket has a PNR too
 *   EXPIRED         —
 *   REGRETTED       —                   waitlist was full
 */
public record BookingResult(BookingStatus status, String seat, Integer position, Instant payBy,
                            String pnr) {

    public static BookingResult held(String seatLabel, Instant payBy) {
        return new BookingResult(BookingStatus.HELD, seatLabel, null, payBy, null);
    }

    public static BookingResult waitlistHeld(int position, Instant payBy) {
        return new BookingResult(BookingStatus.WAITLIST_HELD, null, position, payBy, null);
    }

    public static BookingResult confirmed(String seatLabel, String pnr) {
        return new BookingResult(BookingStatus.CONFIRMED, seatLabel, null, null, pnr);
    }

    public static BookingResult waitlisted(int position, String pnr) {
        return new BookingResult(BookingStatus.WAITLISTED, null, position, null, pnr);
    }

    public static BookingResult expired() {
        return new BookingResult(BookingStatus.EXPIRED, null, null, null, null);
    }

    public static BookingResult regretted() {
        return new BookingResult(BookingStatus.REGRETTED, null, null, null, null);
    }

    public static BookingResult cancelled(String pnr) {
        return new BookingResult(BookingStatus.CANCELLED, null, null, null, pnr);
    }
}
