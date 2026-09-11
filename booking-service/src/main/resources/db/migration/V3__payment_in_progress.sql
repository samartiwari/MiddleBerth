-- Phase 5d: know when someone is in the middle of paying.
--
-- Set when Pay Now creates an order. A hold with a payment under way is not
-- released at the normal deadline — it gets a few extra minutes, because the
-- money may be moments away and releasing the berth now would mean refunding
-- someone who did everything right.
ALTER TABLE booking ADD COLUMN payment_started_at TIMESTAMPTZ;
