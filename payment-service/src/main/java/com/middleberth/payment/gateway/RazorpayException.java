package com.middleberth.payment.gateway;

/**
 * Razorpay said no, or said nothing.
 *
 * Never carries the request that caused it, because that request is authenticated
 * with our secret key and this message ends up in logs.
 */
public class RazorpayException extends RuntimeException {

    public RazorpayException(String message) {
        super(message);
    }

    public RazorpayException(String message, Throwable cause) {
        super(message, cause);
    }
}
