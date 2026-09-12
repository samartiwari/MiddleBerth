-- The number a passenger quotes to anybody: at the counter, on the phone, to a
-- ticket examiner.
--
-- Not the same thing as request_id. A request id is made by the CLIENT before the
-- booking exists, so that a retry cannot book twice — it is unique per user, and
-- two passengers can both use "A7X2". A PNR is issued by US when the ticket is
-- paid for, and is unique across everybody.
ALTER TABLE booking ADD COLUMN pnr VARCHAR(10);

-- Postgres allows many NULLs in a unique index, which is exactly right here:
-- a booking that was never paid for has no PNR.
CREATE UNIQUE INDEX uq_booking_pnr ON booking (pnr);
