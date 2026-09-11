package com.middleberth.payment.gateway;

/**
 * Everything payment-service needs from Razorpay, and nothing more.
 *
 * Two implementations, picked by config:
 *   StubPaymentGateway    tests and load tests — fast, controllable, no account
 *   (5e) the real one     live demo — Razorpay test mode, real checkout page
 *
 * The rest of the service cannot tell which it is talking to. That is what lets
 * load tests measure this system instead of Razorpay's rate limit.
 */
public interface PaymentGateway {

    /**
     * Creates an order at the gateway. The receipt is our own reference for it —
     * shows up on Razorpay's dashboard so a human can find the booking.
     */
    GatewayOrder createOrder(long amountPaise, String currency, String receipt);

    /** The public key the browser needs to open the checkout page. Not a secret. */
    String publicKeyId();
}
