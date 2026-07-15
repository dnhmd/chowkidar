# Chowkidar: Sprint 4 Summary

## Overview

Sprint 4 focused on two production-readiness concerns: API key lifecycle management and observability completeness. The gateway entered this sprint with cryptographically protected but static API keys and structured logging confined to the filter chain. By the end of Sprint 4, Chowkidar supports full key rotation with configurable grace periods, explicit tenant revocation, deprecated key detection, and structured logging across every service and filter layer.

---

## Core Components

### 1. API Key Rotation

API keys were previously immutable after creation. A compromised key had no remediation path short of deleting and recreating the tenant entirely.

Sprint 4 introduces a dedicated rotation endpoint that generates a new key while preserving a configurable grace period for the old one. The flow on rotation: the management API generates a new raw alphanumeric key, computes its HMAC-SHA256 hash, writes the old key hash alongside an expiry timestamp into a new `tenant_api_keys` table, and updates the `tenants` record with the new hash. The raw key is returned once in the rotation response and never stored again. Once the grace window closes, the old key stops resolving.

A dedicated `TenantApiKeysRepository` handles previous key lookups via a separate `findByPreviousApiKeyHash` method. Two repository methods were chosen over a single join query to decouple tenant identity resolution from key rotation metadata, keeping each query independently testable and independently evolvable.

The grace period duration is externalized as `chowkidar.security.api-key-grace-period-hours`, defaulting to 12 hours. Callers still using a deprecated key receive an `X-Api-Key-Deprecated: true` response header. The deprecated flag travels downstream via Reactor context rather than polluting the `TenantContext` or `Tenant` domain records, keeping the flag request-scoped without bleeding lifecycle state into the domain model.

### 2. Tenant Status and Explicit Revocation

Prior to Sprint 4, there was no mechanism to disable a tenant without deleting them entirely. Sprint 4 adds a `status` column to the tenants table with two valid states: `ACTIVE` and `REVOKED`.

Status lives on the tenant, not on the key. Revoking a tenant blocks all access regardless of which key is presented - current or previous. This distinction matters: key expiry is a lifecycle event; revocation is a security event.

The resolution logic in `ContextService` branches explicitly on status:

- `ACTIVE` on current key path → resolve and serve
- `REVOKED` on current key path → 403 Forbidden, short-circuits immediately without checking previous key
- `ACTIVE` on previous key path with valid grace window → resolve and serve with deprecated flag
- `REVOKED` on previous key path → 403 Forbidden
- Expired previous key → 403 Forbidden with explicit expiry message
- No match on either path → 401 Unauthorized

The separation between 401 and 403 is intentional. 401 signals an unrecognized key. 403 signals a recognized but explicitly denied tenant. Mixing them would obscure the reason for rejection in operational logs.

### 3. Key Resolution Schema

Two Flyway migrations support the rotation feature:

**V4** adds a `status VARCHAR NOT NULL DEFAULT 'ACTIVE'` column to `tenants` and creates the `tenant_api_keys` table:

```sql
tenant_api_keys:
  tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE
  previous_api_key_hash VARCHAR
  previous_key_expires_at TIMESTAMP
```

`ON DELETE CASCADE` ensures rotation metadata is cleaned up automatically when a tenant is deleted. The table is keyed by `tenant_id` as the primary key, enforcing a one-to-one relationship. A tenant can have at most one active previous key in a grace period at any time. A second rotation overwrites the previous entry via upsert, replacing the old grace window with a fresh one.

`current_api_key_hash` and `current_key_created_at` were considered for the key table but rejected. Both already exist on `tenants`. Duplicating them would create two sources of truth for the same data.

### 4. Structured Logging Pass

Sprint 3 introduced structured JSON logging inside `RateLimiterFilter` via `doFinally`. Sprint 4 extends structured logging across all four filters and all three services using a consistent format:

```
{Component} | event={event_name} | {key}={value} ...
```

All log statements use `StructuredArguments.keyValue()` from `logstash-logback-encoder`. String interpolation with `{}` placeholders was eliminated across the codebase. MDC was not used — it relies on `ThreadLocal` storage which breaks under reactive thread-hopping.

**Filter coverage:**

- `ContextResolutionFilter`: warns on missing API key (with path and method), warns on resolution failure via `doOnError` (with path and status code), debugs on successful resolution (with tenant ID and deprecated flag)
- `RateLimiterFilter`: warns on rate limit denial (with tenant ID, path, and limiter type), warns on local fallback activation (with tenant ID, path, limiter type, and failure reason) via an extracted `logFallbackToLocalLimiter` private method
- `IdempotencyFilter`: warns on missing idempotency key for a required route (with tenant ID and path), warns on conflict detection (with tenant ID and idempotency key), debugs on cache hit replay (with tenant ID, idempotency key, and status code)
- `ProxyFilter`: logs successful proxy on `doOnSuccess` (with tenant ID, upstream, method, and status code), warns on circuit breaker open via `onErrorResume` (with route ID and upstream), warns on upstream failure via `doOnError` before `onErrorMap` wrapping (with route ID, upstream, and error message)

**Service coverage:**

- `ContextService`: debugs on cache hit, debugs on cache miss before database lookup, warns on deprecated key resolution, warns on circuit breaker open state
- `TenantService`: logs tenant creation, key rotation, and deletion at INFO level with tenant ID
- `RouteService`: logs route creation and deletion at INFO level with tenant ID, route ID, and path; skips logging on reads and updates to avoid noise

---

## Architecture Choices

**`tenant_api_keys` as a separate table over columns on `tenants`:** Adding `previous_api_key_hash` and `previous_key_expires_at` directly to `tenants` was the simpler path. A separate table was chosen because rotation metadata is a distinct concern from tenant identity. It also makes the absence of rotation history structurally explicit: a tenant with no row in `tenant_api_keys` has never rotated their key.

**Status on `tenants`, not on `tenant_api_keys`:** Revocation is a tenant-level decision, not a key-level one. Placing status in the key table would imply that a tenant could be active on one key and revoked on another. That ambiguity is not a supported use case and should not be representable in the schema.

**Reactor context for deprecated flag propagation:** Adding a `deprecated` boolean to `TenantContext` or `Tenant` would conflate request-scoped authentication state with domain model data. The deprecated flag is not a property of who the tenant is — it is a property of how they authenticated on this specific request. Reactor context is the correct scope for request-scoped metadata that needs to travel downstream without polluting domain objects.

**`doOnError` over `switchIfEmpty` for resolution failure logging:** `ContextService.resolve()` never returns empty — all failure paths terminate in `Mono.error`. A `switchIfEmpty` block in `ContextResolutionFilter` would be unreachable dead code. `doOnError` captures the actual exception before it propagates, giving the log statement access to the exception type and status code for structured output.

**Logging at service layer scoped to mutations only:** Read operations in `TenantService` and `RouteService` were deliberately excluded from logging. High-frequency reads produce noise that drowns out signal in operational log streams. Mutations — creates, updates, deletes, rotations — are low-frequency, high-consequence events worth recording.

---

## Package Structure Changes

```plaintext
com.chowkidar.gateway
├── context/model/
│   └── Tenant.java                          # Added status field
├── context/service/
│   └── ContextService.java                  # Full rotation-aware resolution logic, structured logging
├── persistence/entity/
│   ├── TenantEntity.java                    # Added status field
│   └── TenantApiKeysEntity.java             # New — rotation metadata entity
├── persistence/repositories/
│   └── TenantApiKeysRepository.java         # New — findByPreviousApiKeyHash
├── persistence/mappers/
│   └── TenantMapper.java                    # Propagates status through to domain model
├── management/dto/response/
│   └── TenantResponseWithApiKey.java        # Renamed from CreateTenantResponse
├── management/service/
│   └── TenantService.java                   # rotateApiKey(), structured logging
└── filter/
    ├── ContextResolutionFilter.java         # Structured logging
    ├── RateLimiterFilter.java               # Extended structured logging
    ├── IdempotencyFilter.java               # Structured logging
    └── ProxyFilter.java                     # Structured logging

src/main/resources/db/migration/
└── V4__api_key_rotation.sql                 # status column + tenant_api_keys table
```

---

## Next Steps

- Timeout policies per route: Configurable per-route request timeout enforced at proxy layer.
- Fallback URL per route: Secondary upstream target activated on primary failure.
- Health check scheduler: Periodic upstream ping, marks routes degraded on repeated failure.
- Slow request detection: Flag and log requests exceeding a configurable latency threshold.
- IP access control: Support for IP allowlists and blocklists configured per tenant.
- Deployment infrastructure: Dockerfile implementation and docker-compose orchestration setup for the gateway service.
- Public availability: Production-like deployment featuring a live demonstration URL.