-- Add timeout column in table routes
ALTER TABLE routes ADD COLUMN timeout_ms INTEGER NOT NULL DEFAULT 3000;