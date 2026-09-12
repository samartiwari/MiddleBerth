-- One train with real berths, so there is something to book.
--
-- Run after booking-service has started, because Flyway inside that service is
-- what creates these tables. Safe to run again: nothing here duplicates.

INSERT INTO train (number, name) VALUES ('12951', 'Mumbai Rajdhani')
ON CONFLICT (number) DO NOTHING;

-- Tatkal opens the day before travel, so tomorrow is the date that matters.
-- 24 berths in 3A and 72 in sleeper — the real shape of a tatkal quota, and small
-- enough that a few hundred people fighting over it is a genuine contest.
INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT t.id, CURRENT_DATE + 1, '3A', 'B2', n::text, 'FREE'
FROM train t, generate_series(1, 24) AS n
WHERE t.number = '12951'
ON CONFLICT DO NOTHING;

INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT t.id, CURRENT_DATE + 1, 'SL', 'S4', n::text, 'FREE'
FROM train t, generate_series(1, 72) AS n
WHERE t.number = '12951'
ON CONFLICT DO NOTHING;

SELECT coach_class, count(*) AS berths, travel_date FROM seat
GROUP BY coach_class, travel_date ORDER BY coach_class;
