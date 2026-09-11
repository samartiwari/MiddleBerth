-- payment-service owns payments. booking-service never sees card details or
-- talks to the gateway — only this service does.

CREATE TABLE payment (
    id                   BIGSERIAL PRIMARY KEY,

    -- Razorpay's id for the order. The checkout page and every webhook refer to it.
    order_id             VARCHAR(40)  NOT NULL UNIQUE,

    -- Which booking this pays for. booking-service identifies a booking by the
    -- pair (user, request id), so the same pair identifies it here.
    user_id              BIGINT       NOT NULL,
    request_id           VARCHAR(40)  NOT NULL,

    -- Money is always whole paise, never a decimal. 2400.00 rupees is 240000.
    -- Floating point cannot represent 0.10 exactly, and money that is off by a
    -- fraction of a paisa is still wrong.
    amount_paise         BIGINT       NOT NULL,
    currency             VARCHAR(3)   NOT NULL,

    status               VARCHAR(12)  NOT NULL,   -- CREATED / PAID

    -- Filled in when the webhook says the money arrived.
    razorpay_payment_id  VARCHAR(40)  UNIQUE,
    paid_at              TIMESTAMPTZ,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- One order per booking. Clicking Pay Now twice returns the same order
    -- instead of creating two — the same idea as UNIQUE (user_id, request_id)
    -- in booking-service. A failed card and a retry are two ATTEMPTS inside one
    -- Razorpay order, not two orders.
    UNIQUE (user_id, request_id)
);
