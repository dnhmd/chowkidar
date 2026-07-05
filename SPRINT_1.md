# Chowkidar: Sprint 1 Summary

## Overview

Sprint 1 delivered the core request pipeline for Chowkidar, an API gateway built to handle traffic routing and distributed rate limiting across multiple tenants. The architecture intercepts incoming HTTP requests, authenticates them, runs concurrent velocity and volume checks, and proxies the traffic to upstream servers without blocking or buffering data in memory.

---

## Architecture & System Design

The system runs on a reactive, non-blocking stack using Spring WebFlux, Netty, and R2DBC. The request lifecycle passes through a specialized three-stage filter chain:

```plaintext
Client Request → [ContextResolutionFilter] → [RateLimiterFilter] → [ProxyFilter] → Upstream
                      (Auth & Cache)            (Redis Lua)      (Streaming Proxy)
```

1. **ContextResolutionFilter:** Extracts the `X-API-Key` header, queries the in-process cache, and resolves the tenant identity. The context is written directly to the Reactor Context so subsequent filters can access it across asynchronous boundaries.
2. **RateLimiterFilter:** Matches the incoming request path against the tenant's route table. If a match is found, it triggers both the Token Bucket and Sliding Window rate limiters simultaneously using `Mono.zip`.
3. **ProxyFilter:** Rebuilds the target URI using the matching route configuration, strips gateway-specific headers like `Host` and `X-API-Key`, and uses a non-blocking WebClient to stream the response back to the client.

## Project Layout

The repository is structured into distinct modules separating data access, domain models, edge filtering, and rate limiting logic:

```plaintext
com.chowkidar.gateway
├── context/
│   ├── model/         # Domain records (Tenant, Route, TenantContext)
│   └── service/       # Context resolution and in-process cache management
├── filter/            # Reactive gateway filter chain (Auth, Limiting, Proxy)
├── persistence/
│   ├── entity/        # Immutable R2DBC database entities (no Lombok annotations)
│   ├── repositories/  # Reactive Postgres CRUD repositories
│   └── mappers/       # Static translation logic between entities and domain models
├── ratelimit/
│   ├── model/         # Evaluation response data structures
│   └── limiter/       # Token Bucket and Sliding Window implementations
└── config/            # Core infrastructure, RedisScript, and WebClient wiring
```

## Core Infrastructure & Component Breakdown

### 1. In-Process Context Cache

To avoid hitting PostgreSQL on every incoming request, a `ContextService` manages an internal cache using a `ConcurrentHashMap`. 

- **Resolution Pipeline:** When an API key hits the gateway, the service looks for a valid cache entry. If it misses, it triggers a reactive database lookup, collects the routes, populates the cache as a side effect, and passes the context forward.
- **Eviction Strategy:** The cache utilizes a configurable time-to-live setting (`chowkidar.cache.ttl-ms`), defaulting to 30 seconds to balance database protection with routing agility.

### 2. Dual-Engine Distributed Rate Limiting

Rate limiting handles two distinct traffic concerns per route: temporary traffic spikes (velocity) and overall structural limits over time (volume). Both checks run concurrently via separate Redis keys to eliminate lock contention.

#### Token Bucket (Velocity Limiting)

- **Storage Strategy:** Maintained under the `ratelimit:velocity:{tenant_id}:{path}` key structure using a Redis Hash.
- **Mechanism:** Refills are calculated lazily during the request window based on the time elapsed since the last write, avoiding background polling tasks. Keys automatically expire after the maximum timeframe required to fill an empty bucket (`capacity / refill_rate`).

#### Sliding Window Counter (Volume Limiting)

- **Storage Strategy:** Maintained under `ratelimit:volume:{tenant_id}:{path}` using a unified Redis Hash containing the previous window count, current window count, and the active window start timestamp.
- **Mechanism:** Rather than retaining individual request timestamps, an approximation algorithm handles window transitions smoothly in Lua:
$$\text{Estimated Count} = \left(\text{Previous Count} \times \left(1 - \frac{\text{Elapsed Time}}{\text{Window Size}}\right)\right) + \text{Current Count}$$
- **Expiration:** Automatic cleanup occurs via a sliding TTL set to twice the active window duration.

### 3. Non-Buffering Streaming Proxy

The gateway relies on Spring's reactive WebClient built on Netty to handle upstream traffic distribution. The response body is streamed back to the caller as raw DataBuffer chunks, preventing high memory usage during large payloads.

## Architectural Commitments

| Decision                  | Choice                              | Rationale                                                                                                                                                                                     |
|---------------------------|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Concurrency Isolation** | Lua Scripting inside Redis          | Running checks on the Redis server ensures atomic updates across multiple gateway instances, removing Time-of-Check to Time-of-Use race conditions without requiring heavy distributed locks. |
| **Data Storage Layout**   | Single Redis Hash per Limiter       | Storing tokens, counts, and timestamps inside a single hash allows the system to read and update data in a single memory lookup, minimizing network overhead.                                 |
| **Persistence Runtime**   | Reactive R2DBC + JDBC               | Database transactions utilize non-blocking R2DBC drivers to protect the Netty event loops. Flyway migrations run on a separate, dedicated blocking JDBC data source during startup.           |
| **Context Sharing**       | Reactor Context                     | Asynchronous filters cannot rely on traditional `ThreadLocal` storage. Reactor Context provides thread-safe metadata propagation throughout the reactive lifecycle.                           |
| **Resilience Strategy**   | Fail-Open Processing                | If the Redis cluster encounters an outage, the rate-limiting layer degrades gracefully rather than blocking public traffic, prioritizing system availability over strict enforcement.         |

## Operational Verification

The following edge conditions have been integrated into the test suite and confirmed against the active pipeline:

- **Authenticated Traffic:** Requests passing valid API keys map to their upstream targets, returning standard `200 OK` responses along with operational telemetry headers:
  - `RateLimit-Limit`: The route's maximum capacity threshold.
  - `RateLimit-Remaining`: Total available allocation left in the current window.
  - `RateLimit-Reset`: Duration in seconds until capacity fully replenishes.
- **Authentication Failures:** Missing, empty, or unmapped `X-API-Key` headers drop out of the pipeline immediately with a `401 Unauthorized` status code.
- **Route Missing:** Requests targeting unmapped paths bypass the rate limiter and yield a clean `404 Not Found` response wrapper.
- **Exhausted Buckets:** Rapidly executing requests beyond a route's configured parameters blocks traffic instantly, returning a `429 Too Many Requests` status alongside a `Retry-After` header indicating when the next attempt can be made.

## Next Steps

With the core routing layer and caching architecture validated, the next sprint targets administration features, platform hardening, and fallback isolation:

- **Configuration Management API:** Expose CRUD REST endpoints to dynamically register, update, and manage tenants and route maps without requiring database restarts.
- **Resilience4j Circuit Breakers:** Protect the gateway's event loops by wrapping the PostgreSQL connection tier and individual upstream proxy calls in circuit breaker boundaries.
- **Local In-Process Fallbacks:** Implement local rate limiters that activate if the Redis cluster drops offline, maintaining basic boundary protection during infrastructure degradation.
- **Sliding Window Volume Validation:** Expand the end-to-end integration test suite to verify the mathematical accuracy of rolling volume calculations under sustained concurrent stress.
