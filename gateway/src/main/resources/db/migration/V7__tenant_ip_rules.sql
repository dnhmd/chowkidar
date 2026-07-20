-- Add new table for IP allowlist/blocklist
CREATE TABLE tenant_ip_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ip_address VARCHAR NOT NULL,
    action VARCHAR NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);