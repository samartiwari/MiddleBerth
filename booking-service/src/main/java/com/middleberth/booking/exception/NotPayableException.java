package com.middleberth.booking.exception;

public class NotPayableException extends RuntimeException {

    public NotPayableException(String reason) {
        super(reason);
    }
}
