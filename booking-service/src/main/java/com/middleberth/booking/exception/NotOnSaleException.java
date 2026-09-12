package com.middleberth.booking.exception;

import java.time.LocalDate;

/**
 * Asked for a date that has not opened.
 *
 * Without this the request went to the queue, found no berths at all, found no
 * waitlist either — the cap is the berth count, and zero berths means zero
 * waitlist — and came back REGRETTED. Which reads as "the train is full" when
 * the truth is "bookings for that day are not open yet".
 */
public class NotOnSaleException extends RuntimeException {

    public NotOnSaleException(String trainNumber, LocalDate travelDate, String coachClass) {
        super("Bookings for train " + trainNumber + " in " + coachClass + " on " + travelDate
                + " are not open");
    }
}
