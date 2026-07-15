# Chowkidar

A self-hostable API gateway designed to provide reliable control over your traffic layer. It features robust distributed rate limiting capabilities and idempotency support, offering a straightforward, self-managed solution without unnecessary complexity.

Every request flows through a non-blocking, reactive filter chain that handles tenant resolution, parallel Redis rate limit validation, idempotency deduplication, and WebClient proxying, keeping the execution path fast and asynchronous without ever locking an event loop thread. Rate limits run inside atomic Lua scripts on the Redis tier, ensuring accurate traffic calculations across multiple concurrent gateway nodes.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7.2-red?style=flat-square&logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)
![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-green?style=flat-square)

---

## Architecture

```
                     Client Request
                          |
                          v
          +-----------------------------------+
          |         CHOWKIDAR GATEWAY         |
          |                                   |
          |  1. ContextResolutionFilter       |
          |     Validate X-API-Key (HMAC)     |
          |     Load TenantContext from cache |
          |     Cache miss -> Postgres (CB)   |
          |                                   |
          |  2. RateLimiterFilter             |
          |     Token Bucket  -> Redis (Lua)  |
          |     Sliding Window -> Redis (Lua) |
          |     Redis down -> Local JVM       |
          |     Rejected -> 429               |
          |                                   |
          |  3. IdempotencyFilter             |
          |     Distributed lock via Redis    |
          |     Replay cached response        |
          |     Conflict -> 409               |
          |                                   |
          |  4. ProxyFilter                   |
          |     Forward via WebClient         |
          |     Per-route circuit breaker     |
          |                                   |
          |  doFinally -> structured log      |
          +-----------------------------------+
                          |
                          v
                  Tenant Upstream Backend
```

---

## Core Engineering Highlights

- **Parallel Multi-Algorithm Rate Limiting:** The gateway runs token bucket and sliding window counter algorithms simultaneously using a reactive `Mono.zip` construct. Token bucket stops short traffic spikes by evaluating velocity, while the sliding window manages overall quota limits over rolling time frames. Both run natively as Lua scripts in Redis, making the read-compute-write sequence entirely atomic without distributed locking overhead.

- **Resilient Infrastructure Degradation:** If the Redis instance goes offline, the gateway switches automatically to an in-process, JVM-managed token bucket backed by `AtomicReference` state loops. This allows traffic to keep moving through the pipeline under localized safety thresholds until connection to the cache tier recovers.

- **Atomic Distributed Idempotency:** Designed to protect mutation endpoints, the idempotency filter catches duplicate transactions using a Redis `SET NX` execution claim. The initial request claims the lock, routes to the upstream backend, and buffers the outgoing response through a custom `ServerHttpResponseDecorator`. Subsequent duplicates match the active lock and get the cached payload instantly accompanied by an `X-Idempotent-Replay` identifier.

- **Isolated Per-Route Circuit Breakers:** Rather than utilizing a single broad circuit breaker, every route maps to its own distinct `upstream-{routeId}` Resilience4j profile. This prevents an outage or performance dip in one tenant's architecture from spilling over and cutting traffic lines for neighboring setups.

- **Secure Key Hashing at Rest:** Tenant API keys are safeguarded using HMAC-SHA256 calculations against a protected environment secret. Raw access tokens are revealed once on initialization and never written to the data layer. Standard random-salted hashing patterns like BCrypt were explicitly bypassed because they break the deterministic string lookups required for high-speed routing filters.
- 
- **API Key Rotation with Grace Period Enforcement:** Tenant keys can be rotated without causing immediate service disruption. The previous key remains valid for a configurable grace period, giving downstream callers time to propagate the new credential. Requests authenticating with a deprecated key receive an `X-Api-Key-Deprecated: true` response header as an explicit migration signal. Tenant accounts can also be explicitly revoked, blocking all access regardless of which key is presented.

- **End-to-End Reactive Lifecycle:** The entire gateway utilizes Spring WebFlux, R2DBC database configurations, and `ReactiveRedisTemplate` wrappers. Thread blocking is completely eliminated from the entry line to the outbound proxy step, and telemetry pipelines fire inside a decoupled `doFinally` event loop block after client data streams close.

- **Rigorous Integration and Failure Testing:** The core test array features 14 end-to-end integration setups and 3 real-time infrastructure failure scripts utilizing Testcontainers. The test cases programmatically drop Postgres or Redis nodes mid-execution to verify that local in-process fallbacks and circuit breaker state changes trigger seamlessly.

---

## Quick Start

**Prerequisites:** Docker, Java 21, Maven 3.9+

```bash
git clone https://github.com/dnhmd/chowkidar.git
cd chowkidar
docker compose up -d
cd gateway && ./mvnw spring-boot:run
go run echo_server.go   # test upstream on :8081
```

**Create a tenant:**
```bash
curl -X POST http://localhost:8080/management/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "my-service"}'
```
_Response:_
```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service",
  "apiKey": "9f8ac6b4d8384fac843d09a77f1e27d5"
}
```

**Create a route:**
```bash
curl -X POST http://localhost:8080/management/tenants/{id}/routes \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/echo",
    "upstreamUrl": "http://127.0.0.1:8081",
    "capacity": 10,
    "refillRate": 1,
    "volumeLimit": 1000,
    "windowSize": 3600
  }'
```

**Send a request through the gateway:**
```bash
curl http://localhost:8080/echo \
  -H "X-API-Key: 9f8ac6b4d8384fac843d09a77f1e27d5"
```

Every response automatically appends `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` telemetry fields to the client connection. Rate-limited rejections return a 429 status code paired with a defensive `Retry-After` back-off calculation.

---

## Load Test Results

Performance profiles captured using k6 against the active gateway path, executing distributed rate limit validation and live reverse proxy routing:

| Metric                      | Result (Gateway Routing Test)          | Result (Rate Limit Flood Test)         |
|-----------------------------|----------------------------------------|----------------------------------------|
| Sustained Throughput        | 1,160.13 req/sec (200 VUs, 3m)         | 1,118.70 req/sec (100 VUs, 1m)         |
| Total Requests Processed    | 208,732                                | 67,159                                 |
| Minimum Latency             | 1.28ms                                 | 3.54ms                                 |
| Average Latency             | 110.93ms                               | 89.02ms                                |
| Median (p50) Latency        | 95.19ms                                | 67.12ms                                |
| p90 Latency                 | 215.39ms                               | 159.12ms                               |
| p95 Latency                 | 276.98ms                               | 207.26ms                               |
| p99 Latency                 | 384.19ms                               | 365.10ms                               |
| Maximum Latency             | 1.03s                                  | 806.17ms                               |
| Failure Rate (Uncaught/5xx) | 0.00% (0 out of 208,732)               | 0.00% (0 out of 67,159)                |
| Verification Checks         | 100% Passed (417,464 / 417,464 checks) | 100% Passed (134,318 / 134,318 checks) |

> All tests executed on a single WSL2 development machine with the gateway, Redis, PostgreSQL, and the upstream echo server sharing the same host CPU and memory. Production deployment with isolated infrastructure would yield significantly higher throughput and lower tail latency.

---

## Documentation

- [Architecture and Filter Chain](/docs/architecture.md): Structural choices, filter ordering mechanics, and evolutionary updates across engineering milestones.
- [Engineering Decisions](/docs/engineering-decisions.md): Technical tradeoffs, rationales behind framework choices, and post-mortem evaluation of architectural directions.
- [API Reference](/docs/api-reference.md): Full endpoint index and schema payload breakdowns for the internal Management API.
- [Configuration](/docs/configuration.md): Comprehensive variables registry, application properties, and operational defaults.

---

## Build History

Granular breakdown logs detailing development milestones, infrastructure discoveries, and framework iterations reside within `/sprints`.

| Sprint   | Focus                                                                                                                                                                            | Status   |
|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| Sprint 1 | Reactive filter architecture, Token Bucket and Sliding Window Lua scripts, distributed Redis caching, WebClient proxy routing.                                                   | Complete |
| Sprint 2 | Centralized Configuration API, isolated per-route circuit breakers, local JVM fallback limiters, global data validation and uniform exception responses.                         | Complete |
| Sprint 3 | HMAC API key hashing, distributed idempotency filters, structured logging layouts, Actuator monitoring endpoints, Testcontainers integration testing, k6 performance validation. | Complete |
| Sprint 4 | API key rotation with grace period enforcement, tenant revocation, structured logging across filter chain and service layer.                                                     | Complete |
