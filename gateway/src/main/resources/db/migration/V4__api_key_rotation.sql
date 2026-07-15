-- Add status column in table tenants
ALTER TABLE tenants ADD COLUMN status VARCHAR NOT NULL DEFAULT 'ACTIVE';

-- Add new table for API key rotation
CREATE TABLE tenant_api_keys (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    previous_api_key_hash VARCHAR,
    previous_key_expires_at TIMESTAMP
);