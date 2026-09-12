-- Enough berths for a load test: five trains, two classes each.
--
--   3A  24 berths   SL  72 berths      x 5 trains = 480 berths
--
-- The waitlist cap equals the berth count, so about 960 people can get an answer
-- that is not "sorry". Anyone after that is REGRETTED, which is exactly what
-- tatkal looks like at 10:00:01.
--
-- Safe to run again.

INSERT INTO train (number, name) VALUES
    ('12951', 'Mumbai Rajdhani'),
    ('12009', 'Shatabdi Express'),
    ('22691', 'Bengaluru Rajdhani'),
    ('12259', 'Sealdah Duronto'),
    ('12627', 'Karnataka Express')
ON CONFLICT (number) DO NOTHING;

INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT t.id, CURRENT_DATE + 1, '3A', 'B2', n::text, 'FREE'
FROM train t, generate_series(1, 24) AS n
ON CONFLICT DO NOTHING;

INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT t.id, CURRENT_DATE + 1, 'SL', 'S4', n::text, 'FREE'
FROM train t, generate_series(1, 72) AS n
ON CONFLICT DO NOTHING;

SELECT coach_class, count(*) AS berths, count(DISTINCT train_id) AS trains
FROM seat WHERE travel_date = CURRENT_DATE + 1
GROUP BY coach_class;
