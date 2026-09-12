package com.middleberth.booking.exception;

public class NotCancellableException extends RuntimeException {

    public NotCancellableException(String requestId, String status) {
        super("Booking " + requestId + " cannot be cancelled — it is " + status);
    }
}
