package com.middleberth.payment.service;

public enum RefundOutcome {
    REFUNDED,
    /** Already given back — a repeated request does nothing. */
    ALREADY_REFUNDED,
    /** No such order here. */
    UNKNOWN_ORDER
}
