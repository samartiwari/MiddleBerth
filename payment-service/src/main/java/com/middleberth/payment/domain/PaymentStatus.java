package com.middleberth.payment.domain;

public enum PaymentStatus {
    /** An order exists at the gateway. Nobody has paid yet. */
    CREATED,
    /** The gateway says the money arrived. */
    PAID,
    /** Given back — we took it but could not honour the booking. */
    REFUNDED
}
