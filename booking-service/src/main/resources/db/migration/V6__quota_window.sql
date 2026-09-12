-- What each train HAS, as opposed to what is on sale on a given date.
--
-- Seats used to exist only because a seed file inserted them for one date. This
-- describes the train itself, and a job turns it into berths for each new travel
-- date as that date opens.
CREATE TABLE train_quota (
  id           BIGSERIAL  PRIMARY KEY,
  train_id     BIGINT     NOT NULL REFERENCES train(id),
  coach_class  VARCHAR(4) NOT NULL,
  coach        VARCHAR(4) NOT NULL,
  berths       INT        NOT NULL,

  UNIQUE (train_id, coach_class, coach)
);

-- Learn it from the berths that already exist, so a database that is already
-- running keeps working without anybody editing a seed file.
INSERT INTO train_quota (train_id, coach_class, coach, berths)
SELECT train_id, coach_class, coach, count(*)
FROM seat
GROUP BY train_id, coach_class, coach
ON CONFLICT DO NOTHING;

-- Deleting a past travel date means deleting its bookings first, and this is the
-- index that makes that cheap. Without it, the daily purge would scan every
-- booking ever made.
CREATE INDEX idx_booking_travel_date ON booking (travel_date);
