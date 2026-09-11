package com.middleberth.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * What arrives over HTTP. Separate from BookingCommand so the wire format and
 * the service's input can change independently.
 *
 * requestId comes from the client, not from us — it has to survive a retry, and
 * only the client knows it is retrying.
 *
 * There is deliberately NO userId here. Who you are comes from the gateway, which
 * reads it out of your checked token. If it were in the body, a logged-in user
 * could book as anyone by editing one number.
 */
public record BookingRequest(

        @NotBlank(message = "is required")
        @Size(max = 40, message = "must be at most 40 characters")
        String requestId,

        @NotBlank(message = "is required")
        @Size(max = 10, message = "must be at most 10 characters")
        String trainNumber,

        @NotNull(message = "is required")
        LocalDate travelDate,

        @NotBlank(message = "is required")
        @Size(max = 4, message = "must be at most 4 characters")
        String coachClass) {

    /** userId is passed in separately, from the gateway's header. */
    public BookingCommand toCommand(Long userId) {
        return new BookingCommand(requestId, userId, trainNumber, travelDate, coachClass);
    }
}
