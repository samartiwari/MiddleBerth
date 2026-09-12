package com.middleberth.booking.dto;

import com.middleberth.booking.domain.Passenger;

public record PassengerResponse(Long id, String name, String email, String phone) {

    public static PassengerResponse of(Passenger passenger) {
        return new PassengerResponse(passenger.getId(), passenger.getName(),
                passenger.getEmail(), passenger.getPhone());
    }
}
