package com.middleberth.payment.service;

/**
 * What happened to a webhook.
 *
 * Every one of these is answered with a 2xx except REJECTED, so Razorpay stops
 * sending it. The only case that should be retried is "we could not hand it to
 * Kafka" — and that is an exception, which becomes a 500, which Razorpay retries.
 */
public enum WebhookOutcome {
    /** Recorded and announced. */
    PAID,
    /** Already handled — Razorpay delivers the same webhook more than once by design. */
    DUPLICATE,
    /** An event type we do not act on. */
    IGNORED,
    /** No such order here. Retrying will never make it exist. */
    UNKNOWN_ORDER,
    /** Paid a different amount than the order. Needs a human, not a retry. */
    AMOUNT_MISMATCH,
    /** Signature did not check out — not from Razorpay. 400. */
    REJECTED
}
