-- Notes to send, written in the SAME transaction as the booking they describe.
-- Either both land or neither, so a confirmed ticket can never end up with nobody
-- being told. A job picks them up and puts them on Kafka afterwards.
CREATE TABLE outbox (
  id           BIGSERIAL PRIMARY KEY,
  booking_key  VARCHAR(64) NOT NULL,      -- userId|requestId, also the Kafka key
  type         VARCHAR(24) NOT NULL,      -- TICKET_CONFIRMED / BOOKING_CANCELLED
  payload      TEXT        NOT NULL,      -- the message itself, exactly as it will be sent
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at      TIMESTAMPTZ
);

-- Only unsent notes are ever searched for, and while the job keeps up there are
-- almost none. A partial index stays tiny however many notes pile up behind it.
CREATE INDEX idx_outbox_unsent ON outbox (id) WHERE sent_at IS NULL;
