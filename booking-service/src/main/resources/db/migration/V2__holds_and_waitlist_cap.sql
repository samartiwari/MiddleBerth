-- Phase 5a: holds get deadlines, and the waitlist gets a cap.
--
-- V1 has run on real databases now, so this is a NEW migration rather than an
-- edit to V1. Migrations are only ever added on top.


-- A booking is now a lifecycle, not a final state:
--
--   HELD            berth held, waiting for payment      -> CONFIRMED or EXPIRED
--   WAITLIST_HELD   waitlist slot held, waiting to pay   -> WAITLISTED or EXPIRED
--   CONFIRMED       berth, paid
--   WAITLISTED      waitlist slot, paid
--   EXPIRED         did not pay in time, hold released
--   REGRETTED       waitlist was full, nothing was held
--
-- WAITLIST_HELD is 13 characters, so the column has to widen.
ALTER TABLE booking ALTER COLUMN status TYPE VARCHAR(16);


-- pay_by is the deadline the user is TOLD. The hold is actually released a
-- little after it (the cushion), so a payment that lands at 4:59 is not lost to
-- our own processing lag.
ALTER TABLE booking ADD COLUMN pay_by  TIMESTAMPTZ;
ALTER TABLE booking ADD COLUMN paid_at TIMESTAMPTZ;

-- The expiry job asks "which unpaid holds are past their deadline?" every few
-- seconds. Partial, so it only covers the rows that can actually expire.
CREATE INDEX idx_booking_expiry
    ON booking (pay_by)
    WHERE status IN ('HELD', 'WAITLIST_HELD');


-- The waitlist counter. One row per train, date and class.
--
--   wl_issued   what ticket number is next. Only ever goes up.
--   wl_live     how many hold a slot right now. Goes up and down.
--   wl_cap      how many slots there are. Roughly the quota — a waitlist far
--               longer than the number of berths is a promise we cannot keep,
--               and money we would only have to refund.
--
-- Handing out a number is one atomic UPDATE ... RETURNING on this row, so two
-- people can never be given the same one — the count()+1 race is gone by
-- construction, not by luck.
CREATE TABLE quota_counter (
    train_id     BIGINT     NOT NULL REFERENCES train(id),
    travel_date  DATE       NOT NULL,
    coach_class  VARCHAR(4) NOT NULL,
    wl_issued    INT        NOT NULL DEFAULT 0,
    wl_live      INT        NOT NULL DEFAULT 0,
    wl_cap       INT        NOT NULL,
    PRIMARY KEY (train_id, travel_date, coach_class)
);
