-- Tenants: one row per client using the gateway
CREATE TABLE IF NOT EXISTS tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    api_key     VARCHAR(255) NOT NULL UNIQUE,           -- value in X-API-KEY header
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Per-tenant, per-route rate limit config
-- A tenant can have different limits on different routes
CREATE TABLE IF NOT EXISTS routes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    path            VARCHAR(255) NOT NULL,          -- exact path
    upstream_url    VARCHAR(255) NOT NULL,          -- where to forward allowed requests
    capacity        INT NOT NULL,                   -- token bucket max tokens
    refill_rate     INT NOT NULL,                   -- tokens per second
    volume_limit    INT NOT NULL,                   -- max requests per window
    window_size     INT NOT NULL,                   -- window duration in seconds
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, path)
);