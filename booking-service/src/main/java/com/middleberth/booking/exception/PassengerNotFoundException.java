package com.middleberth.booking.exception;

public class PassengerNotFoundException extends RuntimeException {

    public PassengerNotFoundException(Long id) {
        super("No passenger " + id + " on your list");
    }
}
