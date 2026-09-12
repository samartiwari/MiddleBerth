package com.middleberth.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Who the ticket is for, as it was at the moment of booking.
 *
 * Copied onto the booking rather than pointed at, so changing the master list
 * later cannot rewrite a ticket that is already issued.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PassengerSnapshot {

    @Column(name = "passenger_name", length = 80)
    private String name;

    @Column(name = "passenger_email", length = 120)
    private String email;

    @Column(name = "passenger_phone", length = 15)
    private String phone;
}
