# Chowkidar: Sprint 5 Summary

## Overview

Sprint 5 focused on making Chowkidar a more capable and operationally honest gateway. The sprint added five production-grade features: per-route timeout enforcement, fallback URL routing with dedicated circuit breakers, a proactive upstream health check scheduler, slow request detection, and per-tenant IP allowlist/blocklist filtering. It also fixed a latent bug where query parameters were silently dropped during proxying, and resolved a response header mutation error that surfaced after the filter chain was extended.

---

## Core Components

### 1. Per-Route Timeout Enforcement

Prior to Sprint 5, the gateway had no timeout contract with upstream services. A slow upstream could hold a connection open indefinitely, exhausting the reactive thread pool and degrading the entire gateway under load.

A `timeout_ms` column was added to the `routes` table via a V5 Flyway migration, defaulting to 3000ms. The value is configurable per route via `PATCH /management/tenants/{tenantId}/routes/{routeId}/timeout` and globally via `chowkidar.route.default-timeout-ms`.

Timeout enforcement sits in `ProxyFilter` via `WebClient.timeout(Duration)`, placed before `transformDeferred(CircuitBreakerOperator.of(...))`. This placement is critical as it ensures that `TimeoutException` is recorded as a failure by the Resilience4j circuit breaker, allowing repeated timeouts to trip the breaker just as connection failures do.

`TimeoutException` is handled explicitly before `CallNotPermittedException` in the error chain, producing a distinct 503 response: "Upstream request timed out" versus "Upstream circuit breaker open". This distinction is load-bearing for operators diagnosing incidents: a timeout means the upstream is slow, a CB open means it has been repeatedly failing.

### 2. Query Parameter Forwarding Fix

A latent bug was discovered during timeout testing: query parameters were silently dropped before requests reached the upstream. The original upstream URI construction used string concatenation:

```java
String upstream = matchedRoute.upstreamUrl() + matchedRoute.path();
```

This discarded `exchange.getRequest().getURI().getQuery()` entirely. The fix uses `UriComponentsBuilder` to compose the upstream URI correctly:

```java
URI upstreamUri = UriComponentsBuilder
        .fromUriString(matchedRoute.upstreamUrl())
        .replacePath(originalUri.getPath())
        .replaceQuery(originalUri.getQuery())
        .build(true)
        .toUri();
```

### 3. Fallback URL Routing

A nullable `fallback_url` column was added to `routes` via a V6 Flyway migration. Routes without a fallback continue to behave as before: primary failure returns 503. Routes with a fallback attempt the secondary upstream when the primary circuit breaker opens or times out.

The fallback gets its own dedicated Resilience4j circuit breaker instance named `fallback-{routeId}`, completely isolated from the primary `upstream-{routeId}`. This prevents a degraded fallback from affecting primary CB state and vice versa.

Request body replay across primary and fallback was handled by caching the request body flux via `exchange.getRequest().getBody().cache()` before the first proxy attempt. The cached flux replays correctly to the fallback without re-reading from the already-consumed request stream.

The `executeProxy` method was extracted as a private helper shared between primary and fallback paths, eliminating code duplication and keeping the error handling chain symmetric.

### 4. Health Check Scheduler

The health check scheduler provides proactive upstream visibility, detecting failures before real tenant traffic hits a broken upstream rather than learning about them from 503 responses.

The scheduler uses a `RouteHealthRegistry` backed by a `ConcurrentHashMap<UUID, RouteHealthEntry>`. The registry is populated on startup via `ApplicationListener<ApplicationReadyEvent>` by loading all routes from the database, then kept current as routes are created, updated, or deleted via `RouteService` mutations.

Probes run on a `Flux.interval()` stream, firing every `chowkidar.health.interval-ms` (default 30 seconds). Each probe sends a `HEAD` request to `{upstreamUrl}{path}`, checking the actual route path rather than the base URL to catch path-specific failures. Only 2xx responses are considered healthy.

A consecutive failure threshold (`chowkidar.health.failure-threshold`, default 3) prevents single transient failures from flipping route state. The threshold counter is tracked in-memory per route via `ConcurrentHashMap<String, AtomicInteger>`. On first success after failures, the counter resets immediately.

Health state is persisted in Redis under `route:health:{routeId}` as a JSON payload containing status, status code, and timestamp. TTL is set to `3 × intervalMs` so stale state expires naturally if the scheduler dies. Logging only fires on state transitions (`UP → DOWN` or `DOWN → UP`), keeping the log stream silent during steady-state operation.

A `GET /management/tenants/{tenantId}/routes/health` endpoint exposes current health state per route, reading from Redis and returning a `RouteHealthResponse` per route. This provides the visibility the reviewer identified as missing from the project.

### 5. Slow Request Detection

Slow request detection was added to `RateLimiterFilter` inside the existing `doFinally` telemetry block. When `durationMs` exceeds `chowkidar.proxy.slow-request-threshold-ms` (default 2000ms), a structured `WARN` log fires with tenant ID, path, and duration.

This complements the Resilience4j `slow-call-duration-threshold` CB metric. The CB aggregates slow call rates and trips on sustained degradation, while this log captures individual slow requests with full request context for operator investigation.

### 6. IP Allowlist and Blocklist

IP filtering was added as a new `IpFilterFilter` at `@Order(2)`, shifting `RateLimiterFilter` to `@Order(3)`, `IdempotencyFilter` to `@Order(4)`, and `ProxyFilter` to `@Order(5)`.

IP rules are stored in a new `tenant_ip_rules` table (V7 migration) with fields `id`, `tenant_id`, `ip_address`, `action` (`ALLOW`/`BLOCK`), and `created_at`. Rules are managed via a full CRUD API under `/management/tenants/{tenantId}/ip-rules`.

The evaluation logic follows a clear precedence model:
- No rules → allow all traffic
- Only BLOCK rules → allow all except explicitly blocked IPs
- Any ALLOW rules present → allowlist mode: only listed IPs pass, everything else is blocked
- BLOCK always wins over ALLOW for the same IP

Evaluated decisions are cached in Redis under `iprule:{tenantId}:{ipAddress}` with a 30-minute TTL. Cache entries are invalidated immediately on rule create, update, or delete to ensure rule changes take effect without waiting for TTL expiry.

Client IP extraction checks `X-Forwarded-For` first (for requests behind proxies), falling back to `exchange.getRequest().getRemoteAddress()`. IPv6 loopback addresses (`::1`, `0:0:0:0:0:0:0:1`) are normalized to `127.0.0.1`. A `WARN` log fires when the client IP cannot be determined.

### 7. Response Header Mutation Bug Fix

A `UnsupportedOperationException` surfaced after `IpFilterFilter` was added, triggered when `GlobalExceptionHandler` attempted to set `Content-Type` on an already-committed response. Two fixes were applied:

**`GlobalExceptionHandler`:** An `isCommitted()` guard was added at the entry point. If the response is already committed, the handler returns `Mono.empty()`. Error responses for 401, 403, 404, 429, and 503 are unaffected since they fire before any response body is written.

**`ProxyFilter`:** The original implementation called `exchange.getResponse().getHeaders().clear()` followed by `addAll()` when copying upstream response headers. The `clear()` wiped `RateLimit-*` headers set by `RateLimiterFilter`, and `addAll()` then locked the headers as read-only, causing subsequent header writes to throw. The fix replaces `clear()` + `addAll()` with a selective header copy that skips `Transfer-Encoding` and `Content-Length`, preserving gateway-set headers and avoiding the read-only lock.

---

## Architecture Choices

**`HEAD` probe on route path over base URL:** Probing the base URL checks server reachability but not route-level health. A 200 on `/` means nothing if `/payments` is broken. `HEAD` on the route path is more accurate and produces no side effects.

**State-change-only logging for health scheduler:** High-frequency probes logging every result produce noise that drowns signal. Logging only on `UP → DOWN` and `DOWN → UP` transitions keeps the log stream clean and makes state changes immediately visible without filtering.

**Consecutive failure threshold over single-failure flip:** A single failed probe could be a transient network blip. Requiring N consecutive failures before marking a route DOWN prevents false positives that would trigger unnecessary fallback routing or operator alerts.

**Redis cache for IP rule decisions:** IP rule evaluation requires fetching all rules for a tenant and running evaluation logic. Caching the per-IP decision avoids this on every request. The 30-minute TTL is long enough to absorb most traffic patterns while short enough that rule changes propagate within a reasonable window. Explicit invalidation on mutations ensures immediate effect when operators update rules.

**`isCommitted()` guard over error suppression:** Returning `Mono.empty()` on a committed response is not error suppression, it is the correct behavior. The client already received a response. Attempting to overwrite it with an error response would corrupt the connection. The guard makes this explicit rather than letting the exception propagate and crash the event loop thread.

---

## Package Structure Changes

```plaintext
com.chowkidar.gateway
├── health/
│   ├── RouteHealthEntry.java                # Lightweight route probe record
│   ├── RouteHealthRegistry.java             # ConcurrentHashMap-backed route registry
│   └── HealthCheckScheduler.java            # ApplicationReadyEvent-driven Flux.interval probe loop
├── context/model/
│   └── TenantIpRule.java                    # IP rule domain record
├── filter/
│   └── IpFilterFilter.java                  # @Order(2) IP allowlist/blocklist filter
├── management/
│   ├── controller/
│   │   └── TenantIpRuleController.java      # CRUD endpoints for IP rules
│   ├── service/
│   │   └── TenantIpRuleService.java         # IP rule business logic with Redis invalidation
│   └── dto/
│       ├── request/
│       │   ├── CreateIpRuleRequest.java
│       │   └── UpdateIpRuleActionRequest.java
│       └── response/
│           ├── IpRuleResponse.java
│           └── RouteHealthResponse.java
├── persistence/
│   ├── entity/
│   │   └── TenantIpRuleEntity.java
│   ├── repositories/
│   │   └── TenantIpRuleRepository.java
│   └── mappers/
│       └── TenantIpRuleMapper.java

src/main/resources/db/migration/
├── V5__route_timeout.sql                    # timeout_ms column on routes
├── V6__fallback_url.sql                     # fallback_url column on routes
└── V7__tenant_ip_rules.sql                  # tenant_ip_rules table
```

---

## Next Steps

- Dockerfile and docker-compose gateway service
- Integration test coverage
- Public deployment with live demo URL
- Admin portal
- Prometheus + Grafana observability