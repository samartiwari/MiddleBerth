-- Who this account books for. IRCTC calls it the passenger master list: you add
-- people once and pick them at booking time instead of typing them again at
-- 10:00:00, when every second counts.
CREATE TABLE passenger (
  id          BIGSERIAL    PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  name        VARCHAR(80)  NOT NULL,
  email       VARCHAR(120) NOT NULL,
  phone       VARCHAR(15)  NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

  -- The same person twice in one account is a mistake, not a feature. This also
  -- indexes (user_id, ...), which is how the list is read.
  UNIQUE (user_id, name, phone)
);

-- A SNAPSHOT on the booking, not a reference to the row above.
--
-- Editing or deleting a master list entry must not change a ticket that has
-- already been issued — the ticket says who it was issued to. Same reason the
-- outbox note carries the finished message instead of a pointer to one.
ALTER TABLE booking ADD COLUMN passenger_name  VARCHAR(80);
ALTER TABLE booking ADD COLUMN passenger_email VARCHAR(120);
ALTER TABLE booking ADD COLUMN passenger_phone VARCHAR(15);
