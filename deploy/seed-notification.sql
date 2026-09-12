-- Somebody to write to.
--
-- In a real system a user service owns this and notification-service asks it.
-- MiddleBerth has no user service on purpose (login is a demo token at the
-- gateway), so the demo user gets a row here.
INSERT INTO passenger (user_id, email) VALUES
    (5512, 'demo@middleberth.invalid')
ON CONFLICT (user_id) DO NOTHING;

SELECT user_id, email FROM passenger;
