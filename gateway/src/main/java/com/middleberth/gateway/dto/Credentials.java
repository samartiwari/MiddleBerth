package com.middleberth.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What signing up and logging in both take.
 *
 * Eight characters is a low bar, and a low bar that is actually enforced beats a
 * high one that is only in the documentation.
 */
public record Credentials(

        @NotBlank(message = "is required")
        @Email(message = "must be a real email address")
        @Size(max = 120, message = "must be at most 120 characters")
        String email,

        @NotBlank(message = "is required")
        @Size(min = 8, max = 100, message = "must be between 8 and 100 characters")
        String password) {
}
