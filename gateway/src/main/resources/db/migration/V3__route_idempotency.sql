-- Add idempotency check for a route
ALTER TABLE routes ADD COLUMN requires_idempotency BOOLEAN NOT NULL DEFAULT FALSE;