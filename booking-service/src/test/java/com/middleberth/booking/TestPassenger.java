package com.middleberth.booking;

import com.middleberth.booking.dto.PassengerDetails;

/**
 * Somebody for the ticket to be for.
 *
 * Every booking now carries a passenger, and almost no test cares who it is —
 * so they all use this and the intent of each test stays readable.
 */
final class TestPassenger {

    private TestPassenger() {
    }

    static final PassengerDetails SOMEONE =
            new PassengerDetails("Test Passenger", "passenger@example.invalid", "9876543210");

    static PassengerDetails named(String name) {
        return new PassengerDetails(name, name.toLowerCase().replace(' ', '.') + "@example.invalid",
                "9876543210");
    }
}
