-- One train, and what it HAS.
--
-- Not "seats for tomorrow" any more: the daily quota window job turns this into
-- berths for each new travel date, and deletes dates older than a week. So the
-- demo keeps working tomorrow, and next month, without growing.
--
-- 24 berths in 3A and 72 in sleeper — the real shape of a tatkal quota, and small
-- enough that a few hundred people fighting over it is a genuine contest.
INSERT INTO train (number, name) VALUES ('12951', 'Mumbai Rajdhani')
ON CONFLICT (number) DO NOTHING;

INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT t.id, '3A', 'B2', 24 FROM train t WHERE t.number = '12951'
ON CONFLICT DO NOTHING;

INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT t.id, 'SL', 'S4', 72 FROM train t WHERE t.number = '12951'
ON CONFLICT DO NOTHING;

-- Open tomorrow immediately, so the demo has something to sell the moment it
-- starts rather than at the next noon. The job does exactly this, every day.
INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT q.train_id, CURRENT_DATE + 1, q.coach_class, q.coach, n::text, 'FREE'
FROM train_quota q, generate_series(1, 100) AS n
WHERE n <= q.berths
ON CONFLICT DO NOTHING;

SELECT coach_class, count(*) AS berths, travel_date FROM seat
GROUP BY coach_class, travel_date ORDER BY coach_class;
