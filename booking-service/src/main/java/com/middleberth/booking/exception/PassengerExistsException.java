package com.middleberth.booking.exception;

public class PassengerExistsException extends RuntimeException {

    public PassengerExistsException(String name) {
        super(name + " is already on your list");
    }
}
