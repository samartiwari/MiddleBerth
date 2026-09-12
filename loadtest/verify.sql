-- The only result that matters.
--
-- Speed is easy to fake and easy to buy. "Nobody got someone else's berth" is the
-- claim this whole project exists to support, so it is checked against the
-- database after every run, not assumed.

\echo '== 1. THE ONE THAT MATTERS: berths held or confirmed by more than one person'
SELECT seat_id, count(*) AS bookings
FROM booking
WHERE seat_id IS NOT NULL AND status IN ('HELD', 'CONFIRMED')
GROUP BY seat_id HAVING count(*) > 1;

\echo '== 2. a berth marked taken with nobody holding it, or the other way round'
SELECT s.status AS seat_status, count(*) AS berths
FROM seat s
LEFT JOIN booking b ON b.seat_id = s.id AND b.status IN ('HELD', 'CONFIRMED')
WHERE (s.status IN ('HELD', 'CONFIRMED') AND b.id IS NULL)
   OR (s.status = 'FREE' AND b.id IS NOT NULL)
GROUP BY s.status;

\echo '== 3. two people given the same waitlist number on the same train'
SELECT train_id, travel_date, coach_class, waitlist_pos, count(*)
FROM booking
WHERE waitlist_pos IS NOT NULL AND status IN ('WAITLIST_HELD', 'WAITLISTED')
GROUP BY train_id, travel_date, coach_class, waitlist_pos
HAVING count(*) > 1;

\echo '== 4. what everyone got (REGRETTED will not appear — it is never stored)'
-- Most of a tatkal rush gets nothing, and nothing is what gets written for them.
-- A regret holds no berth, no waitlist number and no money, so the row would have
-- said only "no" — at the cost of keeping a name, an email and a phone number.
-- The rows below are the people who actually got something.
SELECT status, count(*) FROM booking GROUP BY status ORDER BY count DESC;

\echo '== 5. berths: taken vs free'
SELECT status, count(*) FROM seat WHERE travel_date = CURRENT_DATE + 1
GROUP BY status;

\echo '== 6. the waitlist counters (wl_live must never exceed wl_cap)'
SELECT count(*) AS counters, sum(wl_issued) AS issued, sum(wl_live) AS live,
       sum(wl_cap) AS cap, bool_and(wl_live <= wl_cap) AS within_cap
FROM quota_counter;

\echo '== 7. outbox: anything still unsent'
SELECT count(*) FILTER (WHERE sent_at IS NULL) AS unsent, count(*) AS total FROM outbox;
