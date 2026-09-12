package com.middleberth.booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Who the ticket is for. The email is where it gets sent, so it is checked
 * properly rather than accepted and lost later — a confirmed ticket with a
 * mistyped address is a mail nobody ever receives.
 */
public record PassengerDetails(

        @NotBlank(message = "is required")
        @Size(max = 80, message = "must be at most 80 characters")
        String name,

        @NotBlank(message = "is required")
        @Email(message = "must be a real email address")
        @Size(max = 120, message = "must be at most 120 characters")
        String email,

        @NotBlank(message = "is required")
        @Pattern(regexp = "[6-9][0-9]{9}", message = "must be a 10 digit Indian mobile number")
        String phone) {
}
