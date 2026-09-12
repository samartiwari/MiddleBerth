-- Accounts.
--
-- "user" is a reserved word in Postgres, hence app_user.
--
-- The password is NOT here. Only a BCrypt hash of it, which cannot be turned
-- back into the password — so a stolen database does not hand over anybody's
-- password, and not even we can read it.
CREATE TABLE app_user (
  id             BIGSERIAL    PRIMARY KEY,
  email          VARCHAR(120) NOT NULL,
  password_hash  VARCHAR(72)  NOT NULL,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

  -- One account per address, and the database is what enforces it rather than a
  -- check beforehand — two people signing up at the same instant would both pass
  -- a check and both insert.
  CONSTRAINT uq_app_user_email UNIQUE (email)
);
