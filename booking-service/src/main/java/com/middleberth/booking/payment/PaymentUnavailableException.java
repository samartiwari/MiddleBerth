package com.middleberth.booking.payment;

public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(Throwable cause) {
        super("Payment service unavailable", cause);
    }
}
