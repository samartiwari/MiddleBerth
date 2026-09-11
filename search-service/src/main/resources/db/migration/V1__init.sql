-- search-service owns its own copy of the train list.
--
-- Deliberately duplicated from booking-service rather than shared. Each service
-- owning its data is the whole point — search can be scaled, deployed or
-- rewritten without touching booking, and a browsing flood can never reach
-- booking's database.
--
-- No routes, stations or schedules. initial.md rules those out, and they would
-- demonstrate nothing about this system.

CREATE TABLE train (
    id      BIGSERIAL PRIMARY KEY,
    number  VARCHAR(10)  NOT NULL UNIQUE,
    name    VARCHAR(100) NOT NULL
);

INSERT INTO train (number, name) VALUES
    ('12951', 'Mumbai Rajdhani'),
    ('12009', 'Shatabdi Express'),
    ('22691', 'Bengaluru Rajdhani'),
    ('12259', 'Sealdah Duronto'),
    ('12627', 'Karnataka Express');
