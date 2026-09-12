package com.middleberth.booking.exception;

import java.time.LocalDate;

/**
 * Nothing left — no berth, and the waitlist is at its cap too.
 *
 * This used to be found out the long way round: the request went on the queue,
 * waited its turn, took a database transaction to discover there was nothing, and
 * then wrote a row whose entire content was the word "no". On the load run that
 * was two thirds of all the traffic doing all of the work to be told it had
 * failed.
 *
 * Now it is answered at the door, in one read, and nothing is written down at
 * all. A "no" is not a booking, so it does not need a booking row.
 */
public class WaitlistFullException extends RuntimeException {

    public WaitlistFullException(String trainNumber, LocalDate travelDate, String coachClass) {
        super("Train " + trainNumber + " in " + coachClass + " on " + travelDate
                + " is full — berths and waitlist are both gone");
    }
}
