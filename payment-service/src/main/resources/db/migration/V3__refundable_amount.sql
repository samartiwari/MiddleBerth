-- How much of a payment is the ticket, as opposed to the convenience fee.
--
-- The gateway takes its cut on the way IN and never returns it, so a payment that
-- collected exactly the fare can never refund exactly the fare. The fee on top is
-- what covers that, which means the two numbers are no longer the same and the
-- refundable one has to be written down at the time the order is made.
--
-- Existing rows were charged the fare and nothing more, so for them the whole
-- amount was refundable. Backfilled that way, then made NOT NULL.
ALTER TABLE payment ADD COLUMN refundable_paise BIGINT;
UPDATE payment SET refundable_paise = amount_paise WHERE refundable_paise IS NULL;
ALTER TABLE payment ALTER COLUMN refundable_paise SET NOT NULL;
