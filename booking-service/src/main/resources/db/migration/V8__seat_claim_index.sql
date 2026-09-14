-- The index every berth claim reads, with id added on the end.
--
-- The claim asks for the free berth with the lowest id on one train, date and
-- class. idx_seat_lookup found those berths but not in id order, so Postgres often
-- judged it cheaper to walk the whole seat table by id and throw away every row
-- that was not this train. Berths are stored train by train, so a claim on one of
-- the last trains read past some 170,000 rows to lock one, and which plan Postgres
-- picked depended on the statistics of the moment. Under a steady 1,000 bookings a
-- second the same code sometimes kept up and sometimes collapsed to under 200.
--
-- With id as the last column, the index hands back this train's free berths
-- already in id order, so the lowest one is the first entry whichever plan is
-- chosen. The same four columns still come first, so it covers everything
-- idx_seat_lookup did. That one goes, and a claim still updates one index, not two.
--
-- Built without CONCURRENTLY: the seat table here holds a few days of berths and
-- this runs at startup. On a large live table it would be CREATE INDEX CONCURRENTLY,
-- outside a transaction.
CREATE INDEX idx_seat_claim
    ON seat (train_id, travel_date, coach_class, status, id);

DROP INDEX idx_seat_lookup;
