package com.middleberth.gateway.auth;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * An account.
 *
 * The user id in every JWT is this row's id, which is why the bookings and
 * passengers already in the system keep working — they were always keyed on a
 * number, and now that number belongs to somebody.
 */
@Table("app_user")
@Getter
public class AppUser {

    @Id
    private Long id;

    private String email;

    /** BCrypt. Never the password itself, and never logged. */
    private String passwordHash;

    private OffsetDateTime createdAt;

    public AppUser(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
