# Chowkidar: Sprint 2 Summary

## Overview

Sprint 2 hardened the Chowkidar API Gateway by delivering a centralized Configuration Management API, platform-level resilience through Resilience4j circuit breakers, local in-process fallback rate limiters for Redis outage scenarios, and verified end-to-end sliding window volume enforcement.

## Core Components

### 1. Configuration Management API

Administrative endpoints are isolated under the `/management` path prefix. To prevent management traffic from entering the gateway loop, the `GatewayPaths.isManagementPath()` utility bypasses the primary gateway filter chain entirely at the top of execution.

- **Tenant Management:** Exposes endpoints to create tenants, list profiles, fetch by ID, update names via `PATCH`, and execute deletions.
- **Route Management:** Supports per-tenant route creation, retrieval, deletion, and targeted modifications. Upstream changes and rate parameters are isolated into discrete `UpdateRouteUpstreamRequest` and `UpdateRouteRateRequest` DTOs to avoid null-field ambiguity.
- **Key Lifecycle & Cache Invalidation:** API keys are server-generated using unhyphenated UUID structures. To eliminate stale configuration windows, any state mutation triggers an immediate, targeted purge via `ContextService.invalidate(apiKey)`. Route paths are immutable once created to prevent orphaned data keys in Redis. 

### 2. Validation & Uniform Error Handling

- **Data Constraints:** Inbound request DTOs enforce field-level constraints using standard Bean Validation annotations, ensuring required string definitions are not blank and numerical limit metrics remain positive.
- **Overriding Error Handlers:** A global `GlobalExceptionHandler` hooks into the system with an execution rank of `@Order(-1)`, preempting default framework logic.
- **Payload Structure:** Validation anomalies, missing assets, and system faults map into a consistent JSON payload containing tracking identifiers, timestamps, specific HTTP status codes, and localized reason messages.

### 3. Fault Isolation via Circuit Breakers

Two distinct Resilience4j circuit breaker instances protect database and upstream connections independently:

| Metric                | Postgres Failure Domain | Upstream Proxy Failure Domain |
|-----------------------|-------------------------|-------------------------------|
| **Type**              | Core Insfrastructure | Tenant Specific |
| **Scope**             | Unified Global Boundary | Dynamic Per-Route Instance (`upstream-{routeId}`) |
| **Failure Threshold** | 50% | 60% |
| **Slow Call Cutoff**  | 2 seconds | 5 seconds |
| **Open State Window** | 60 seconds | 30 seconds |
| **Sliding Window Size** | 20 invocations | 20 invocations |

- **Storage Protection:** The `Postgres` instance wraps lookups inside `ContextService.resolve()`, catching both tenant and route connectivity failures under a single shield.
- **Upstream Protection:** Isolating upstream circuit breakers per route ensures that a single tenant's degraded backend can never impact the proxy performance of adjacent tenants.
- **Graceful Degradation:** Trip states translate cleanly to users, mapping underlying connection issues to descriptive `503 Service Unavailable` status responses.

### 4. Local In-Process Fallback Rate Limiting

To keep the gateway operational during unexpected Redis outages, a pure Java `LocalRateLimiter` was engineered to act as an immediate, network-independent safety net.

- **State Architecture:** Utilizes a `ConcurrentHashMap` storing atomic references to an in-memory `BucketState` record containing raw token balances and historical timestamp counters.
- **Atomic Modifications:** Thread-safe updates are achieved using a non-blocking `compareAndSet` retry loop, mimicking the atomic execution pattern of single-threaded Redis Lua configurations.
- **Transparent Filter Routing:** Integration into the RateLimiterFilter uses reactive recovery hooks to activate fallbacks transparently:

```java
Mono<RateLimitResult> tokenBucketCheck = tokenBucketLimiter.limit(tenant, matchedRoute)
    .onErrorResume(ex -> localRateLimiter.limit(tenant, matchedRoute));

Mono<RateLimitResult> slidingWindowCheck = slidingWindowLimiter.limit(tenant, matchedRoute)
    .onErrorResume(ex -> localRateLimiter.limit(tenant, matchedRoute));
```

_Because the limiters fall back independently, a failure in the distributed velocity check triggers local degradation only for that specific strategy, preserving basic boundary control._

### 5. Volume Verification End to End

Real-world behavioral checks validated macro volume caps under tight operational limits (configured at 5 requests max per 60-second window). Under sustained target load, the sixth invocation correctly failed with a `429 Too Many Requests` status while writing accurate rate-limit tracking fields, confirming the underlying Lua scripts evaluate sliding windows accurately under traffic pressure.

## Architecture Choices

- **Consolidated Prefix Routing:** Isolating administrative actions under `/management` keeps routing logic cleanly separated from client proxy loops, preventing custom logic leaks within individual filters.
- **Immediate Purge over Lazy Lifespan:** Configuration modifications write infrequently compared to high-speed reads. Purging cache blocks instantly upon write operations is preferred over allowing stale routing rules to persist for a 30-second TTL window.
- **Separated Data Transfer Objects:** Utilizing discrete payload definitions for upstream adjustments versus rate adjustments prevents field mutation confusion and avoids processing ambiguous null values.
- **Placing Resilience Barriers on Adapters:** Circuit breakers protect technical network boundaries and belong exclusively on infrastructure adapters (`ContextService` and `ProxyFilter`), keeping core domain modules framework-free.
- **Atomic Non-Blocking Fallbacks:** Leveraging `AtomicReference` compare loops provides concurrent thread safety without introducing blocking locks that would stall underlying reactive event loops.

## Package Structure Changes

```plaintext
com.chowkidar.gateway
├── exception/
│   ├── ErrorResponse.java            # Standardized exception payload
│   └── GlobalExceptionHandler.java   # Overriding reactive error interceptor
├── management/
│   ├── controller/                   # Administrative endpoints for tenants/routes
│   ├── service/                      # Configuration state management
│   └── dto/                          # Strict request/%response schemas
└── ratelimit/
    └── limiter/
        └── LocalRateLimiter.java     # Concurrent Java fallback limiter
```

## Next Steps

- **Cryptographic API Key Protection:** Upgrade credential security by transitioning to HMAC-SHA256 key hashing to protect keys at rest. Implement enterprise lifecycle features including automated key rotation schedules, explicit expiration windows, and granular scope permissions to restrict keys to specific routes.
- **Asynchronous Telemetry Pipeline:** Replace the temporary `doFinally` placeholder in the `RateLimiterFilter` with a non-blocking telemetry engine. Every handled request will emit metric events asynchronously, completely decoupled from the active client proxy path.
- **Structured Contextual Logging:** Standardize a production-ready logging format across the filter chain. Every execution block will automatically capture and map critical tracking variables, including unique Request IDs, validated Tenant IDs, matched Route paths, and exact downstream request latency.
- **Production Actuator Instrumentation:** Expose native Spring Boot Actuator endpoints to gain real-time visibility into operational health. This includes tracking Resilience4j circuit breaker states, Redis cache cluster connectivity, and reactive Postgres database pool health.
- **Distributed Idempotency Filter:** Build an exactly-once request execution layer using Redis for distributed storage. The filter will intercept retry storms by creating short-lived distributed locks and deduplicating incoming client requests on a per-tenant basis, guaranteeing safe mutations on downstream services.
- **End-to-End Integration Arrays:** Build comprehensive integration test suites to verify the entire reactive filter chain. Testing will explicitly validate Redis Lua script boundaries under concurrency and ensure administrative mutations trigger immediate, targeted cache purges.
- **Automated Failure Scenario Tests:** Programmatically simulate infrastructure failures to verify built-in resilience boundaries. Scenarios will confirm that sudden Redis cluster dropouts trigger smooth local fallbacks, and simulated upstream timeouts correctly trip individual per-route circuit breakers.
- **High-Throughput Load Testing:** Execute sustained concurrency profiles using frameworks like k6 or Gatling to establish baseline reactive throughput. Benchmarks will validate Netty event loop efficiency, memory consumption footprints, and rate-limiting accuracy under intense traffic pressure.
