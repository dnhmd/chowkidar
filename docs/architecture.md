# Architecture

This document traces the technical evolution of Chowkidar's architecture across its sprints. Reading it in sequence maps the structural build out of the system alongside the engineering logic and technical constraints that drove every key design pivot.

---

## Sprint 1: The Core Filter Chain

The foundational design challenge centered on intercepting inbound request flows efficiently without introducing processing latency or thread contention.

The solution utilizes a structured `WebFilter` pipeline built natively on Spring WebFlux. WebFilters wrap the request lifecycle asynchronously, allowing individual filter blocks to inspect, transform, or enrich request states without ever blocking an event loop thread. The filter pipeline executes sequentially, letting individual modules either pass execution downstream or short-circuit the flow by returning a direct response.

The initial architecture established three core filters:

```
ContextResolutionFilter (@Order 1)
RateLimiterFilter       (@Order 2)
ProxyFilter             (@Order 3)
```

The specific order satisfies strict technical dependencies. The gateway cannot run rate validations before verifying credential authenticity, and it cannot forward network payloads to backends until velocity checks pass cleanly.

### Context Resolution

The initial filter is responsible for validating client API keys and loading corresponding tenant routing rules. The early architecture separated these responsibilities into discrete authentication and configuration resolution filters to enforce a separation of concerns.

This setup proved inefficient. Both filters were forced to query the same data assets using identical key properties, resulting in two distinct Postgres round trips per request. Recognizing that credential verification and profile resolution are part of the same primary lookup, the components were unified into a single `ContextResolutionFilter`. The gateway checks the key; if it matches an active profile, the tenant data loads instantly; if not, the request terminates immediately.

The fully resolved `TenantContext` record travels downstream inside a `ReactorContext` instance. This structure serves as the reactive equivalent to a standard `ThreadLocal` storage variable, ensuring contextual variables map accurately as requests shift across various asynchronous event loop threads. The filter writes data using the `.contextWrite()` operator, which intentionally hooks into the sequence after `chain.filter()` because Reactor context properties propagate backward from the subscriber up the execution stream.

### In-Process Cache

Executing relational database queries against Postgres on every incoming request would severely limit gateway throughput. A non-blocking cache-aside mechanism was introduced within the `ContextService` using a manual `ConcurrentHashMap` configuration featuring a 30-second TTL.

Standard Spring Cache abstractions were explicitly bypassed. The underlying code inside Spring Cache utilizes blocking synchronization mechanisms that would compromise the Netty event loop. The custom implementation pairs a thread-safe `ConcurrentHashMap` with a `CachedContext` value wrapper that carries an absolute `expiryTime` timestamp, maintaining a completely non-blocking execution path.

When a cache miss occurs, the reactive sequence calls `findByApiKey()` followed by a secondary route repository query. The system compiles the route data stream into a list, instantiates the master `TenantContext`, and populates the cache structure as a synchronous side effect using the doOnNext operator. The raw incoming API key serves as the primary cache lookup key.

### Rate Limiting

The gateway evaluates traffic quality by processing two distinct rate limiting strategies concurrently to cover distinct consumption boundaries. The token bucket algorithm checks short-term velocity to catch abrupt traffic surges, while a sliding window counter tracks macro volume consumption to enforce billing cycles or quota allocations.

Both operations run directly inside Redis using custom Lua scripts to achieve absolute transactional atomicity. Rate limiting logic naturally requires a read-compute-write execution cycle. In a distributed deployment with multiple gateway nodes, separate instances could read identical counters simultaneously, compute that capacity remains available, commit decremented values, and inadvertently allow duplicate traffic volumes. This creates a classic Time-of-Check to Time-of-Use (TOCTOU) race condition. Because Redis runs Lua scripts within a single-threaded execution context, the entire read-compute-write loop runs as one atomic command blocks out interleaving operations.

Velocity data maps to `ratelimit:velocity:{tenant_id}:{path}`, while volume counts reside under `ratelimit:volume:{tenant_id}:{path}`. Splitting these states across independent key prefixes prevents lock contention inside Redis, allowing both Lua scripts to execute in parallel via `Mono.zip` without serialization delays.

The token bucket script uses a lazy replenishment model rather than relying on background polling routines. Upon request entry, the script computes the exact duration elapsed since the last recorded interaction and adds the corresponding fraction of tokens mathematically. This removes background cron overhead and derives token balances from two persisted metrics: `tokens` and `last_refill_ts`.

The sliding window counter tracks three metrics: `prev_count`, `current_count`, and `current_window_start`. The script measures the elapsed time inside the active window; if the window duration expires, the system shifts the current metrics to the historical slot and resets the active counters. The system calculates active volume allocation using a standard approximation formula:

```
estimated = (prev_count × (1 - elapsed/window_size)) + current_count
```

This logic assumes traffic distribution across the previous window remained uniform. While front-loaded usage bursts can cause minor underestimations, real-world accuracy tracking holds variation under 1% for standard traffic patterns.

### Proxy

The `ProxyFilter` uses a reactive Spring `WebClient` backend built over Netty transport layers to hand off requests to target upstream destinations. The target backend destination details are extracted from the matched `Route` object, which the `RateLimiterFilter` injects into the active `ReactorContext` stream upon passing validation.

The filter systematically strips out the `X-API-Key` and `Host` headers before dispatching the payload. Removing the key prevents secret leaks to downstream backends, while dropping the host header ensures the gateway does not pass along internal routing values (like `localhost:8080`) that upstream web servers routinely reject.

Incoming responses stream directly back to the client as raw `DataBuffer` chunks. The gateway completely avoids memory body buffering, meaning massive downstream data streams pass through the gateway boundaries without causing memory inflation or heap pressure.

---

## Sprint 2: Configuration API and Resilience

With the core filter pipeline established, Sprint 2 focused on creating management layers to configure tenants dynamically while introducing fault tolerance boundaries against infrastructure drops.

### The Management Path Problem

The configuration management endpoints needed to run on the same network port as the core gateway filter chain. This introduced a pipeline conflict: the primary gateway filters would intercept administrative requests and drop them with a `401 Unauthorized` response since new managers would not possess an API key.

Isolating the configuration API on an independent port was considered, which mirrors architectures found in systems like Kong or Tyk. However, initializing separate concurrent `HttpServer` runtimes inside a single Spring Boot application added unnecessary structural complexity for the target scope.

The selected solution isolates administrative routes behind a dedicated `/management` path prefix. A central `GatewayPaths` helper exposes an `isManagementPath()` check that all active gateway filters invoke at the absolute entry line of execution. This keeps path exclusion rules dry and consolidated.

As development progressed into Sprint 3, internal `/actuator` endpoints required identical exclusions. The `GatewayPaths` utility was extended with an `isActuatorPath()` check and a unified `shouldBypassFilters()` method. The primary filter implementations remained completely unchanged, containing all path adjustment details within the utility class.

### Cache Invalidation

Introducing dynamic routing changes highlighted a critical data consistency risk. When an operator updates a route's token capacity via a `PATCH /management/tenants/{id}/routes/{id}/rate` request, the internal cache layer would continue serving the cached `TenantContext` profile for the remainder of its 30-second TTL window.

Accepting stale configurations or reducing global TTL limits was rejected due to performance tradeoffs. The system uses immediate cache invalidation upon data writes. Because configuration adjustments occur infrequently compared to high-speed proxy actions, purging the cache on mutation guarantees real-time rule enforcement.

The `ContextService` exposes a direct `invalidate(String apiKeyHash)` function. Every service method that alters tenant or route state triggers this method immediately after confirming database transaction writes. Following the cryptographic updates introduced in Sprint 3, the cache key structure migrated to use the API key hash rather than the raw secret. The invalidation routine utilizes the pre-existing `tenantEntity.apiKeyHash` value from the database entity, avoiding redundant runtime hash calculations.

### Circuit Breakers

The system implements two distinct Resilience4j circuit breaker instances to isolate clear infrastructure failure boundaries.

The Postgres circuit breaker guards the data resolution layer inside `ContextService.resolve()`. Placing the breaker at the service tier rather than wrapping raw repository interfaces ensures that both tenant lookups and route index mappings fall under a unified boundary. If Postgres experiences query degradation, the circuit trips immediately for the combined lookup sequence rather than managing multiple independent repository states.

The upstream circuit breaker instantiates dynamically per route using an `upstream-{routeId}` naming pattern. This structure prevents noisy-neighbor issues. If a specific tenant backend experiences latency drops or timeouts, only its corresponding circuit breaker trips open. Every adjacent tenant continues passing proxy traffic without interruption. A shared circuit breaker layout would have allowed a single unstable backend to compromise the availability of the entire gateway plane.

The configuration metrics reflect these differing operational environments:

| Metric Parameter | Postgres Failure Domain                | Upstream Proxy Domain             |
|------------------|----------------------------------------|-----------------------------------|
| Strategy Focus   | Core Internal Infrastructure           | Dynamic Tenant External Service   |
| Trip Velocity    | "Fast Tripping, Extended Sleep Window" | "Higher Tolerance, Fast Recovery" |

### Local Fallback Rate Limiter

The initial design handled Redis connectivity drops implicitly: failing Lua script executions triggered standard 500 errors, exposing infrastructure issues directly to consumers. This represented a fragile, fail-closed posture.

The `LocalRateLimiter` acts as an `onErrorResume` fallback catch for both distributed limiters. If the Redis layer drops for any reason, the request drops into an in-process local token bucket loop.

The local limiter uses a `ConcurrentHashMap<String, AtomicReference<BucketState>>` structure. The `AtomicReference` pair operates inside a non-blocking compare-and-set retry loop, providing concurrent thread safety across the JVM without introducing blocking synchronized blocks. This design mirrors the execution safety of Redis Lua scripts within local memory space. The loop samples the current atomic state, calculates token changes, and attempts to write the update via `compareAndSet`. If a parallel thread updates the reference mid-calculation, the swap fails safely, and the loop retries the operation instantly against the new state.

---

## Sprint 3: Security, Idempotency, and Observability

Sprint 3 focused on moving the platform from a functional prototype to a hardened, production-ready gateway architecture.

### API Key Security

The early framework versions preserved tenant API keys in plaintext formats, meaning a data layer breach would instantly compromise the credentials of all configured tenants.

BCrypt was evaluated as a potential hashing candidate since it offers strong protection against brute-force attacks. However, it was rejected due to a functional limitation: BCrypt incorporates a random salt value into every unique hash computation, meaning the same input string yields different output hashes across evaluations. This breaks deterministic database indexing. Finding a tenant would require loading the entire table into memory and running `BCrypt.verify()` against every row for every inbound request—a bottleneck that would devastate gateway throughput at scale.

The gateway uses HMAC-SHA256 calculations combined with a secure server-side secret key. HMAC guarantees deterministic hashing output, allowing the gateway to execute fast lookups via a simple `WHERE api_key_hash = ?` query using the incoming token's calculated hash value. If the database is compromised, the hashes remain completely secure without the server-side environment secret.

The API payload schemas were updated to enforce security at the type layer. The system uses a dedicated `CreateTenantResponse` (which carries the raw API key) exclusively upon tenant creation. Standard queries route through a distinct `TenantResponse` model that completely omits key fields, ensuring the raw key token is exposed exactly once upon generation.

### Idempotency

The idempotency engine represents the most intricate filter addition introduced in Sprint 3.

Consider a client submitting a non-idempotent payment request where the network connection drops immediately after the upstream backend processes the transaction but before the response returns to the client. The client retries the action, risking a duplicate transaction. The gateway must provide exactly-once execution safety by tracking the results of the initial transaction and replaying the cached output to subsequent duplicate attempts.

The primary engineering challenge lies in handling concurrent duplicates where twin requests bearing identical idempotency keys reach the filter chain at the exact same millisecond. If both check the cache tier simultaneously, both would find an empty state, and both would proceed to execute on the upstream backend, defeating the idempotency layer.The hard part is the concurrent duplicate. Two requests with the same idempotency key arriving simultaneously. Both check Redis. Both see nothing. Both forward to the upstream. The payment fires twice despite the idempotency layer.

The gateway resolves this by using an atomic Redis command: `SET key value NX PX ttl`. The `NX` modifier ensures the write succeeds only if the target key does not already exist. Of two parallel incoming requests, exactly one successfully writes a `PROCESSING` token into Redis. The duplicate request fails the write, identifies the active execution lock, and halts.

Capturing the asynchronous response stream for future replay required implementing a custom `ServerHttpResponseDecorator`. In a standard reactive WebFlux flow, data buffers stream from the `ProxyFilter` directly out to the network socket. The decorator wraps the reactive response object before execution and intercepts the `writeWith` method. It listens to the outgoing byte stream chunks, compiles a clean JSON cache record containing the response headers and body payload into Redis, and seamlessly flushes the original bytes out to the client socket using `super.writeWith()`.

The gateway restricts caching exclusively to successful 2xx responses. If an upstream backend returns an infrastructure error or a 500 fault, the filter immediately deletes the active `PROCESSING` key, allowing clients to retry the execution path safely.

Idempotency checks remain opt-in per route using a `requires_idempotency` boolean data column. Enforcing global idempotency across all routes would discard valid mutating actions that omit the header on endpoints that do not require deduplication. The per-route configuration flag gives operators explicit, fine-grained control.

### Observability

Structured JSON logging hooks directly into the `RateLimiterFilter` via the reactive `doFinally` operator. The `doFinally` positioning ensures execution metrics record across all terminal lifecycle events, including successful completions, network errors, and client cancellations mid-flight. The request `startTime` metric is captured synchronously at the absolute entry line of the filter block before any reactive deferrals occur, ensuring that `durationMs` calculations capture the full end-to-end processing latency of the request.

Standard Mapped Diagnostic Context (MDC) setups were rejected for trace distribution. MDC relies on `ThreadLocal` storage variables, which break down in reactive environments as tasks hop between different event loop threads. The gateway passes trace arguments to log calls using `StructuredArguments` from the `logstash-logback-encoder` library.

---

## Current Filter Chain (Sprint 3)

The active filter pipeline functions with the following configuration:

```
ContextResolutionFilter  (@Order 1)  — Executes HMAC credential checks and loads TenantContext
RateLimiterFilter        (@Order 2)  — Evaluates token bucket and sliding window metrics with JVM fallbacks
IdempotencyFilter        (@Order 3)  — Enforces distributed locking and manages response replay routines
ProxyFilter              (@Order 4)  — Standardizes WebClient routing and applies per-route circuit breakers
```

Every component leverages the reactive `ReactorContext` map to share state properties cleanly with downstream elements. `ContextResolutionFilter` populates the core `TenantContext`. The `RateLimiterFilter` resolves and writes the target `Route` properties. The `IdempotencyFilter` inspects both contexts without mutating the stream, and the final `ProxyFilter` extracts the validated `Route` data to direct the outbound `WebClient` connection.