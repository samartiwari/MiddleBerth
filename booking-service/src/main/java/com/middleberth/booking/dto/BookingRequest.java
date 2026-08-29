package com.middleberth.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * What arrives over HTTP. Separate from BookingCommand so the wire format and
 * the service's input can change independently.
 *
 * requestId comes from the client, not from us — it has to survive a retry, and
 * only the client knows it is retrying.
 */
public record BookingRequest(

        @NotBlank(message = "is required")
        @Size(max = 40, message = "must be at most 40 characters")
        String requestId,

        @NotNull(message = "is required")
        @Positive(message = "must be positive")
        Long userId,

        @NotBlank(message = "is required")
        @Size(max = 10, message = "must be at most 10 characters")
        String trainNumber,

        @NotNull(message = "is required")
        LocalDate travelDate,

        @NotBlank(message = "is required")
        @Size(max = 4, message = "must be at most 4 characters")
        String coachClass) {

    public BookingCommand toCommand() {
        return new BookingCommand(requestId, userId, trainNumber, travelDate, coachClass);
    }
}
