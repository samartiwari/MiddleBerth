package com.middleberth.booking.exception;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(String requestId) {
        super("No booking " + requestId);
    }
}
