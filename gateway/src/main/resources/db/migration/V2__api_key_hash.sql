-- API keys are now to be stored as hashed
ALTER TABLE tenants RENAME COLUMN api_key TO api_key_hash;