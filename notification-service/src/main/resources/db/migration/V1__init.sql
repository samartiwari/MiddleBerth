-- Who to write to. In a real system this lives in a user service; here it is a
-- small table so the demo has somewhere to look.
CREATE TABLE passenger (
  user_id  BIGINT       PRIMARY KEY,
  email    VARCHAR(120) NOT NULL
);

-- One row per mail actually sent.
--
-- Messages arrive at least once — the outbox in booking-service may hand the same
-- note over twice — so this is what stops a passenger being told twice. The UNIQUE
-- constraint is the guarantee; the check before sending is only an optimisation.
CREATE TABLE sent_mail (
  id          BIGSERIAL   PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  request_id  VARCHAR(40) NOT NULL,
  type        VARCHAR(24) NOT NULL,
  sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, request_id, type)
);
