-- The address now travels in the event, taken from the booking itself.
--
-- This table was a guess: one row per user, seeded by hand, with nothing to keep
-- it in step with the bookings it was supposed to describe. booking-service knows
-- who the ticket is for — it asked them — so it says so in the message and this
-- service stops having an opinion.
DROP TABLE passenger;
