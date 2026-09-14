package com.middleberth.booking.service;

import java.time.Duration;

/**
 * How long a waiting page is told to leave it before asking again.
 *
 * Every page used to ask every half second, however long it had already waited.
 * That is harmless while answers come back at once, which is most of the time: one
 * poll for every booking at 500 bookings a second. Past the rate the booking
 * threads keep up with, it is what turns falling behind into falling over. At 1,000
 * a second polls were 86% of all requests, thirteen for every booking, and the CPU
 * spent saying "not yet" was taken from the bookings they were waiting for.
 *
 * So the wait grows with the waiting. A booking decided in its first moments is
 * still found on the first or second ask; one stuck in a queue is asked about
 * every few seconds instead of twice a second.
 */
public final class PollPacing {

    private PollPacing() {
    }

    static Duration after(Duration waited) {
        if (waited.compareTo(Duration.ofSeconds(2)) < 0) {
            return Duration.ofMillis(500);
        }
        if (waited.compareTo(Duration.ofSeconds(5)) < 0) {
            return Duration.ofSeconds(1);
        }
        if (waited.compareTo(Duration.ofSeconds(15)) < 0) {
            return Duration.ofSeconds(2);
        }
        return Duration.ofSeconds(4);
    }

    /**
     * Nothing records when it was accepted: Redis lost the note, or never had one.
     * Not so eager that it feeds a queue, not so slow that it keeps somebody waiting.
     */
    static Duration forUnknownWait() {
        return Duration.ofSeconds(1);
    }
}
