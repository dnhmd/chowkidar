# Chowkidar: Sprint 3 Summary

## Overview

Sprint 3 focused exclusively on backend hardening. The gateway entered this sprint with a working filter chain and configuration API but no security around credentials, no observability, no test coverage, and no validation of how the system behaves when infrastructure falls apart. All of that changed. By the end of Sprint 3, Chowkidar has cryptographically protected API keys, an idempotency layer with distributed locking, structured JSON logging, Actuator health endpoints, a full integration test suite, programmatic failure scenario tests, and load test benchmarks validating the reactive design under concurrency.

---

## Core Components

### 1. API Key Protection via HMAC-SHA256

API keys were previously stored and compared in plaintext. Anyone with read access to the database had every key. This was fixed by transitioning to HMAC-SHA256 hashing using a server-side secret.

The flow on creation: the application generates a raw alphanumeric key, computes its HMAC hash using a configurable `chowkidar.security.hmac-secret`, stores only the hash in the database, and returns the raw key to the caller exactly once. After that, the raw key is gone. On every subsequent request, the incoming `X-API-Key` header is hashed and compared against the stored hash — the raw key never touches the database.

BCrypt was considered and rejected. BCrypt's random salt means two hashes of the same input are never equal, making database lookups impossible without a full table scan. HMAC with a shared server secret produces deterministic output, preserving the ability to query by hash while still making the database useless to an attacker without the secret.

A new Flyway migration renamed the `api_key` column to `api_key_hash` to make the intent explicit at the schema level. The tenant creation response was also split into `CreateTenantResponse` (which includes the raw key) and `TenantResponse` (which does not), so read endpoints never accidentally expose credential material.

### 2. Idempotency Filter

The idempotency filter provides exactly-once execution guarantees for mutating HTTP requests. It sits at `@Order(3)` between the rate limiter and proxy, and only activates for POST, PUT, and PATCH requests carrying an `X-Idempotency-Key` header.

The core challenge with idempotency in a distributed system is the concurrent duplicate — two requests with the same key arriving simultaneously, both checking Redis, both seeing nothing, both forwarding to the upstream. The solution is Redis `SET key value NX PX ttl`, which is atomic. Only one request can claim the key. The other sees the key already exists and knows a request is in flight.

The filter operates in two modes depending on whether the key claim succeeds:

On first request, a `ServerHttpResponseDecorator` intercepts the response body as it flows back from the upstream. The decorator captures the status code and body bytes, serializes them into a JSON record with a `COMPLETED` status, and stores the record in Redis with a 30-minute TTL. The original bytes are then written back to the client without modification.

On duplicate request, the filter reads the stored value. If it is `PROCESSING`, the first request is still in flight and the duplicate receives a `409 Conflict`. If it is `COMPLETED`, the stored status code and body are replayed directly, and an `X-Idempotent-Replay: true` header is added to the response so the client knows the result came from cache.

Idempotency enforcement is opt-in per route. A `requires_idempotency` boolean column was added to the routes table via a new Flyway migration. Routes with this flag set to `true` reject mutating requests missing the idempotency header with a `400 Bad Request`. Routes without the flag pass through without enforcement, keeping the filter transparent for APIs that do not need it.

### 3. Structured JSON Logging

Request logging was added directly into `RateLimiterFilter` using the `doFinally` operator — the TODO placeholder from Sprint 1 is now a real telemetry emission. `doFinally` fires on every terminal signal including completion, error, and cancellation, ensuring no request goes unlogged regardless of outcome.

The `logstash-logback-encoder` library was added to produce structured JSON output compatible with log aggregators like ELK, Datadog, and Grafana Loki. Each log line captures the tenant ID, request path, HTTP method, response status, total duration in milliseconds, and the Reactor signal type that terminated the pipeline.

A `logback-spring.xml` configuration separates log output by Spring profile. The default profile writes plain text to console for local readability. The production profile writes JSON to console and to a rolling file appender with 30-day retention and a 2GB total size cap.

### 4. Actuator Health and Metrics

Spring Boot Actuator was already on the classpath from Sprint 1 but was invisible because the gateway filter chain was intercepting `/actuator` requests and rejecting them with 401. A new `isActuatorPath()` method was added to `GatewayPaths` alongside the existing `isManagementPath()`, and all four filters were updated to call a unified `shouldBypassFilters()` check.

With that in place, the health endpoint now exposes the status of every infrastructure dependency — PostgreSQL via R2DBC, Redis via Lettuce, disk space, and all Resilience4j circuit breaker instances. The metrics endpoint surfaces named metrics including full Resilience4j circuit breaker state, R2DBC connection pool depth, Redis command latency via Lettuce, JVM heap and GC activity, and HTTP server request statistics.

### 5. Integration Test Suite

A `GatewayIntegrationTests` class was built using Testcontainers to spin up real PostgreSQL and Redis instances for each test run. The Spring context boots against these containers via `@DynamicPropertySource`, and `WebTestClient` fires real HTTP requests through the full filter chain. Nothing is mocked.

The suite covers fourteen scenarios across the happy path and failure cases:

- Tenant creation and validation, including blank name rejection and missing field rejection
- Route creation and validation, including blank path rejection and blank upstream rejection
- Authenticated gateway requests succeeding end to end
- Rate limit exhaustion returning 429 with correct headers
- Invalid API key returning 401
- Missing API key returning 401
- Unmatched route returning 404
- Idempotency replay returning the cached response with `X-Idempotent-Replay: true`

### 6. Failure Scenario Tests

A separate `GatewayFailureScenarioTests` class was created to verify system behavior when infrastructure actually fails. These tests use `ApplicationContextInitializer` to wire container ports into the Spring context before startup, giving each test the ability to stop and restart containers mid-execution.

Three scenarios were verified:

**Redis failure and local fallback:** Redis is stopped after a successful warm-up request. The next request succeeds via the `LocalRateLimiter` fallback, confirming the `onErrorResume` chain in `RateLimiterFilter` activates transparently. The test is annotated with `@DirtiesContext` so the Spring context is rebuilt after the container state changes.

**Cold cache with Postgres down:** A successful request populates the cache. Postgres is then stopped. A second request succeeds from cache. After a short sleep with a reduced TTL of 1 second, the third request finds the cache expired and Postgres unreachable, returning 503. Two fixes emerged from this test — the `minimum-number-of-calls` threshold is overridden in test properties to trip the circuit breaker faster, and a catch-all `onErrorMap` was added to `ContextService` to translate raw R2DBC connection exceptions to 503 instead of 500.

**Unreachable upstream:** A route is created pointing at `localhost:9999` where nothing is listening. The request returns 503 instead of 500 after a matching `onErrorMap` was added to `ProxyFilter` to translate `WebClientRequestException` to a meaningful service unavailable response.

### 7. Load Testing with k6

Two k6 scripts were written to validate the reactive design under real traffic pressure.

The throughput test ran 10 virtual users continuously for 30 seconds against the full filter chain. Results: 90 requests per second sustained throughput, p95 latency of 7.93ms, and a 0% failure rate across 2,710 total requests. The p95 threshold of 500ms was comfortably met with headroom to spare.

The rate limit test fired 20 concurrent requests from 10 virtual users against a route configured with a capacity of 5. Exactly 5 requests succeeded and 15 were rate limited, confirming the Lua scripts maintain atomicity under concurrent load. No race conditions were observed — the token bucket behaved correctly even with 10 simultaneous callers.

---

## Architecture Choices

**HMAC over BCrypt for API keys:** BCrypt's random salting prevents deterministic lookup, making it unsuitable for a scenario where the key must be found by hash. HMAC with a server secret produces consistent output while keeping the database useless without the secret.

**Idempotency as opt-in per route:** Forcing idempotency on all routes would break simple read-heavy APIs that never need it. Marking individual routes as requiring idempotency gives tenants explicit control without changing the default behavior.

**`ServerHttpResponseDecorator` for response capture:** WebFlux writes response bodies as streaming byte buffers. The only way to intercept them without breaking the reactive pipeline is to wrap the response object before the chain executes. The decorator pattern keeps the interception transparent to all downstream filters.

**`ApplicationContextInitializer` for failure tests:** `@DynamicPropertySource` runs once at context creation and cannot respond to container restarts. `ApplicationContextInitializer` fires earlier in the Spring lifecycle and wires ports directly into the environment before any beans are created, giving failure tests the control they need without hacks.

**Separate `GatewayFailureScenarioTests` class:** Mixing failure tests that stop containers with normal integration tests that expect containers to be up would cause test ordering issues and flaky behavior. Keeping them in a dedicated class with its own container lifecycle is cleaner and makes failures easier to diagnose.

---

## Package Structure Changes

```plaintext
com.chowkidar.gateway
├── security/
│   └── HmacUtils.java                       # HMAC-SHA256 key hashing utility
└── filter/
    └── IdempotencyFilter.java               # Distributed idempotency with response replay

src/main/resources/
├── logback-spring.xml                       # Profile-aware structured logging config
└── db/migration/
    ├── V2__api_key_hash.sql                 # Rename api_key to api_key_hash
    └── V3__route_idempotency.sql            # Add requires_idempotency column

src/test/java/com/chowkidar/gateway/
├── GatewayIntegrationTests.java             # 14 end-to-end integration tests
└── GatewayFailureScenarioTests.java         # 3 infrastructure failure scenario tests

load-test/
├── gateway_load_test.js                     # Throughput and latency benchmark
└── rate_limit_load_test.js                  # Concurrent rate limit accuracy test
```

---

## Next Steps

- SSE-based live dashboard streaming real-time rate limit metrics per tenant
- Frontend metrics visualization
- Postgres failure graceful degradation with conservative fallback `TenantContext`
- API key rotation and expiry
- Public deployment and live demo setup