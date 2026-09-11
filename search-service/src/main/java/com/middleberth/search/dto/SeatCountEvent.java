package com.middleberth.search.dto;

import java.time.LocalDate;

/**
 * What booking-service publishes on the seat-counts topic.
 *
 * A copy of booking-service's record, on purpose — the two services share a
 * message contract, not a class. Sharing a jar would couple their release
 * cycles together, which is exactly what separate services are meant to avoid.
 */
public record SeatCountEvent(String trainNumber,
                             LocalDate travelDate,
                             String coachClass,
                             int freeSeats) {
}
