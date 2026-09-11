package com.middleberth.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * { "status": "AVAILABLE", "freeSeats": 23 }
 * { "status": "WAITLIST" }
 * { "status": "UNKNOWN" }     Redis has no number yet
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailabilityResponse(String trainNumber,
                                   String name,
                                   LocalDate travelDate,
                                   String coachClass,
                                   String status,
                                   Integer freeSeats) {
}
