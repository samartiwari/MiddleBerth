-- Five trains for the load test, and what each one has.
--
--   3A  24 berths   SL  72 berths      x 5 trains = 480 berths
--
-- The waitlist cap equals the berth count, so about 960 people can get an answer
-- that is not "sorry". Anyone after that is turned away at the door, in one read,
-- with nothing queued and nothing written down — which is exactly what tatkal
-- looks like at 10:00:01, and most of the traffic.
--
-- Safe to run again.

INSERT INTO train (number, name) VALUES
    ('12951', 'Mumbai Rajdhani'),
    ('12009', 'Shatabdi Express'),
    ('22691', 'Bengaluru Rajdhani'),
    ('12259', 'Sealdah Duronto'),
    ('12627', 'Karnataka Express')
ON CONFLICT (number) DO NOTHING;

INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT t.id, '3A', 'B2', 24 FROM train t
ON CONFLICT DO NOTHING;

INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT t.id, 'SL', 'S4', 72 FROM train t
ON CONFLICT DO NOTHING;

-- Tomorrow, on sale now — the load test runs immediately, not at noon.
INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT q.train_id, CURRENT_DATE + 1, q.coach_class, q.coach, n::text, 'FREE'
FROM train_quota q, generate_series(1, 100) AS n
WHERE n <= q.berths
ON CONFLICT DO NOTHING;

SELECT coach_class, count(*) AS berths, count(DISTINCT train_id) AS trains
FROM seat WHERE travel_date = CURRENT_DATE + 1
GROUP BY coach_class;
