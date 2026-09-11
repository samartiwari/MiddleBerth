package com.middleberth.booking.dto;

import java.time.LocalDate;

/**
 * "This train, date and class now has N free berths."
 *
 * The absolute number, not a delta, on purpose. A lost, duplicated or
 * out-of-order event is corrected by the next one. A delta would drift forever
 * after a single lost message.
 */
public record SeatCountEvent(String trainNumber,
                             LocalDate travelDate,
                             String coachClass,
                             int freeSeats) {
}
