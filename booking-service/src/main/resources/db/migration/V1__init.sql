-- MiddleBerth booking-service — initial schema.
--
-- Three tables: the trains, the individual berths on them, and the bookings
-- people make against those berths.


-- A train. Just enough to identify one.
CREATE TABLE train (
    id      BIGSERIAL PRIMARY KEY,
    number  VARCHAR(10)  NOT NULL UNIQUE,   -- 12951
    name    VARCHAR(100) NOT NULL           -- Mumbai Rajdhani
);


-- One physical berth on one train on one date.
--
-- This is the table the whole project is about. A row here is a thing exactly
-- one person can end up holding, and the seat claiming query locks rows in this
-- table with FOR UPDATE SKIP LOCKED.
CREATE TABLE seat (
    id           BIGSERIAL PRIMARY KEY,
    train_id     BIGINT      NOT NULL REFERENCES train(id),
    travel_date  DATE        NOT NULL,
    coach_class  VARCHAR(4)  NOT NULL,   -- 3A, 2A, SL   ("class" is reserved)
    coach        VARCHAR(4)  NOT NULL,   -- B2
    seat_no      VARCHAR(6)  NOT NULL,   -- 31
    status       VARCHAR(12) NOT NULL,   -- FREE / HELD / CONFIRMED

    -- The same berth cannot exist twice on the same train and date.
    UNIQUE (train_id, travel_date, coach_class, coach, seat_no)
);

-- Supports the claim query: find a FREE seat on this train, date and class.
-- Without this, every booking attempt scans the whole seat table, and at 10:00
-- there are thousands of them per second.
CREATE INDEX idx_seat_lookup
    ON seat (train_id, travel_date, coach_class, status);


-- One person's attempt to book. Either they got a berth (HELD) or they did not
-- (WAITLISTED).
CREATE TABLE booking (
    id            BIGSERIAL PRIMARY KEY,

    -- The client generates this before sending, and reuses it on every retry
    -- of the same click. Scoped to the user below, so one person's id can never
    -- collide with another's and hand them somebody else's booking.
    request_id    VARCHAR(40) NOT NULL,

    user_id       BIGINT      NOT NULL,

    -- What they asked for. Stored here rather than looked up through seat_id,
    -- because a waitlisted booking has no seat — and without these three
    -- columns there would be no way to know which train it is waiting for.
    train_id      BIGINT      NOT NULL REFERENCES train(id),
    travel_date   DATE        NOT NULL,
    coach_class   VARCHAR(4)  NOT NULL,

    seat_id       BIGINT      REFERENCES seat(id),   -- NULL when waitlisted
    status        VARCHAR(12) NOT NULL,              -- HELD / WAITLISTED
    waitlist_pos  INT,                               -- NULL unless WAITLISTED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The guarantee against double booking on a retry. Checking first is only
    -- an optimisation; this is what actually stops it.
    UNIQUE (user_id, request_id)
);

-- Finds the next person in line when a berth is released, and lets us count
-- the existing queue when handing out a new waitlist position.
CREATE INDEX idx_waitlist
    ON booking (train_id, travel_date, coach_class, waitlist_pos)
    WHERE status = 'WAITLISTED';
