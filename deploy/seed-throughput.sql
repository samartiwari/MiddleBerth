-- Three hundred trains for the steady-rate test, big enough that it never runs out.
--
--   3A  4 coaches x 72   SL  4 coaches x 72    = 576 berths a train
--   x 300 trains = 172,800 berths, and as many waitlist places
--
-- The spike's seed (seed-load.sql) has 480 berths, all handed out in about a
-- second. That measures how fast a small quota goes, not how fast the system can
-- book: the run is over before anything is pushed. Here the fastest rate
-- loadtest/steady.sh tries, 4,000 a second for a minute, is 240,000 attempts, or
-- about 400 for each train and class against 576 berths each. So every attempt
-- takes the full path (queue, claim, write) and none is turned away at the door
-- because everything is already gone.
--
-- Why many trains and not five big ones: a train, date and class is one Kafka key,
-- one key lands on one partition, and one partition is read by one thread. The
-- spike's ten keys reach eight of the fifteen partitions, so seven booking
-- threads never have anything to do. Six hundred keys reach all of them.
--
-- Safe to run again.

INSERT INTO train (number, name)
SELECT (90000 + n)::text, 'Load Train ' || n
FROM generate_series(1, 300) AS n
ON CONFLICT (number) DO NOTHING;

INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT t.id, c.coach_class, c.coach, 72
FROM train t
CROSS JOIN (VALUES ('3A', 'B1'), ('3A', 'B2'), ('3A', 'B3'), ('3A', 'B4'),
                   ('SL', 'S1'), ('SL', 'S2'), ('SL', 'S3'), ('SL', 'S4')) AS c (coach_class, coach)
WHERE t.name LIKE 'Load Train %'
ON CONFLICT DO NOTHING;

-- Tomorrow, on sale now — the test runs immediately, not at noon.
INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
SELECT q.train_id, CURRENT_DATE + 1, q.coach_class, q.coach, n::text, 'FREE'
FROM train_quota q
CROSS JOIN LATERAL generate_series(1, q.berths) AS n
ON CONFLICT DO NOTHING;

SELECT coach_class, count(*) AS berths, count(DISTINCT train_id) AS trains
FROM seat WHERE travel_date = CURRENT_DATE + 1
GROUP BY coach_class;
