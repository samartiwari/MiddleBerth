package com.middleberth.booking.domain;

/**
 * A booking is a lifecycle. Everything starts as a hold with a deadline.
 *
 *   HELD ----------- paid ----> CONFIRMED
 *     \------------- late ----> EXPIRED
 *
 *   WAITLIST_HELD -- paid ----> WAITLISTED --- a berth frees up ---> CONFIRMED
 *     \------------- late ----> EXPIRED
 *
 *   REGRETTED   the waitlist was full, so nothing was held at all
 *   CANCELLED   the passenger gave it up. A berth goes straight to the next paid
 *               waitlister, exactly as when a hold expires, and paid money goes back.
 */
public enum BookingStatus {
    HELD,
    WAITLIST_HELD,
    CONFIRMED,
    WAITLISTED,
    EXPIRED,
    REGRETTED,
    CANCELLED;

    /** Unpaid, with a deadline — what the expiry job looks for. */
    public boolean isHold() {
        return this == HELD || this == WAITLIST_HELD;
    }
}
