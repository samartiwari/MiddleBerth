package com.middleberth.payment.gateway;

import java.util.Optional;

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

    /**
     * Refunds a captured payment in full, and returns the refund's id.
     *
     * MUST be safe to call twice for the same payment: if it was already refunded,
     * return that refund instead of paying out again. A crash between refunding and
     * recording it means this WILL be called twice sometimes, and paying a customer
     * back twice is still getting money wrong.
     */
    String refundInFull(String paymentId, long amountPaise);

    /**
     * The captured payment on this order, if the gateway has one. The safety net
     * for a webhook that never arrived. Razorpay: fetch payments for an order.
     */
    Optional<CapturedPayment> findCapturedPayment(String orderId);
}
