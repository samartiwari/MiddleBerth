-- Phase 5d: refunds. A payment we took but could not honour is given back.
ALTER TABLE payment ADD COLUMN refund_id   VARCHAR(40);
ALTER TABLE payment ADD COLUMN refunded_at TIMESTAMPTZ;
