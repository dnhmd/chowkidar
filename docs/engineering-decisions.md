# Engineering Decisions

This document details the core architectural and technical choices made while developing Chowkidar. It records why specific frameworks and design patterns were adopted, the alternatives evaluated, and the design modifications made across development phases.

---

## Architecture & Core Engine

### Why Spring WebFlux over Spring MVC

The gateway's primary responsibility is intercepting incoming requests and forwarding them upstream. Consequently, the application spends a significant amount of its execution life cycle waiting: on Redis evaluations, Postgres data resolution, and response streams from upstream backends. In a traditional blocking stack (like Spring MVC on a thread-per-request model), every waiting request keeps an operating system thread locked. Threads are memory-expensive; a server allocated 200 threads can only manage 200 concurrent in-flight transactions before requests begin queuing.

WebFlux leverages Netty’s non-blocking I/O multiplexing model. While a request waits on external database inputs, its allocated thread is instantly released to process concurrent incoming requests. This architectural model allows a compact pool of core threads to multiplex thousands of parallel in-flight transactions. For an API gateway application, this non-blocking approach matches the highly I/O-bound nature of the workload.

The primary tradeoff is increased code complexity. Reactive engineering requires thinking in non-linear event streams rather than sequential steps. Code missteps, such as calling a blocking execution loop within a reactive pipeline, can stall the underlying Netty event loop threads. Several early dependencies required manual isolation or custom rewrites because they introduced blocking routines underneath their high-level APIs, including Flyway database migration hooks and traditional Spring Cache layers.

### Why WebClient over Raw Netty Transport

The gateway proxy layer could have been built using raw Netty handlers to provide low-level control over socket configurations, connection-level rate limiting rules, protocol tuning, and timeout enforcements. While this approach provides fine-grained visibility into slow-request detection and connection pool tuning parameters, it was rejected.

Building on raw sockets introduces a large development surface area for problems that are already solved by mature frameworks. Managing protocol variance between HTTP/1.1 and HTTP/2, handling custom TLS handshakes, and overseeing raw byte buffer lifecycles are all complex tasks that present potential vulnerability vectors. `WebClient` provides a high-level reactive HTTP client built on Netty that abstracts these complexities away behind a clean API. Should the proxy layer ever become a system bottleneck, the lower-level Netty foundations remain accessible for direct customization.

## State Management & Distributed Rate Limiting

### Why Redis Lua Scripts over MULTI/EXEC Transactions

The read-compute-write rate limiting validation sequence must behave as a single atomic transaction. The gateway had two potential avenues to achieve this inside Redis: Lua script executions or optimistic `MULTI/EXEC` transaction blocks combined with `WATCH` parameters.

Optimistic locking using `MULTI/EXEC` transactions monitors target data keys for outside updates. If another gateway node alters the watched key between the read step and the `EXEC` commit step, the active transaction fails entirely, forcing the gateway to rerun the operation. Under heavy concurrent load, this behavior triggers severe retry storms that degrade processing latency.

Lua scripts execute natively inside Redis's single-threaded command processor. Because no other command can interleave while a Lua script runs, the read-compute-write cycle achieves absolute atomicity without distributed locking structures or retry loops. This design results in highly predictable processing latencies under sustained traffic concurrency.

### Why Two Separate Redis Keys for Token Bucket and Sliding Window

The initial architectural design consolidated all five rate limiting fields (`tokens`, `last_refill_ts`, `prev_count`, `current_count`, and `current_window_start`) inside a singular Redis Hash key per route definition, aiming to minimize database lookups.

This layout created concurrent performance bottlenecks. Although Redis Lua scripts maintain absolute transaction atomicity, two separate scripts requesting access to the identical data key are forced to run sequentially rather than concurrently. If the token bucket script and the sliding window script target the same hash key structure, they lock each other out. Every request paid the latency cost of these scripts waiting in line.

Separating these fields into independent key spaces (`ratelimit:velocity:...` and `ratelimit:volume:...`) creates isolated implicit locks. This key layout allows both Lua scripts to execute concurrently using `Mono.zip` without competing for the same memory address. The small network cost of dispatching two operations instead of one is offset by eliminating the sequential script bottleneck.

## Security & Reliability Architecture

### Why HMAC-SHA256 Hashing over BCrypt for API Keys

BCrypt represents the industry standard for protecting user passwords because it is intentionally slow and utilizes a random salt to ensure identical passwords generate entirely unique hash footprints. However, using it for API key lookups in a high-speed gateway pipeline presents a major structural limitation.

Because BCrypt produces a distinct hash output for every evaluation of the same key string, the gateway cannot execute deterministic database queries like `WHERE api_key_hash = ?`. To authenticate an inbound request, the application would have to fetch every single tenant profile into memory and run `BCrypt.verify()` against each record sequentially until it found a match. While manageable with a handful of tenants, this full table scan pattern causes a complete system collapse under production scaling.

```plaintext
Incoming Request Key
       │
       ├──► BCrypt Hashing (Random Salt)  ──► Cannot index; requires full table scan.
       │
       └──► HMAC-SHA256 (Deterministic)   ──► Exact match index lookup (Fast).
```

HMAC-SHA256 combined with a secure server-side key provides a deterministic hashing pattern. The same key input combined with the server secret consistently yields the same hash output, enabling high-speed database indexing. If a database breach occurs, the stored hashes remain unreadable without the server-side environment secret.

The remaining weakness: if the server secret is compromised, all hashes are compromised. This is why the secret should be rotated periodically and stored in a secrets manager rather than a plain environment variable. That is left for a future sprint.

### Why Fail-Open Logic During Redis Outages

When a distributed cache tier drops offline, the default instinct is to fail closed by rejecting all inbound traffic because rate validation cannot be processed. This approach is highly fragile.

Failing closed elevates a single infrastructure drop into a complete tenant outage. If the Redis tier experiences a temporary network blip, all downstream tenant traffic drops to zero. For teams running critical production systems, like payment APIs, this design causes instant checkout failures and missed transactions. Furthermore, a fail-closed posture presents a major security risk: disrupting a single cache instance successfully executes a Denial of Service (DoS) attack against every backend service shielded by the gateway.

Chowkidar implements a fail-open posture backed by local in-process fallback structures. If the Redis connection drops, the `LocalRateLimiter` activates transparently using `onErrorResume` blocks. The system switches calculations to localized memory, maintaining baseline boundary protection and allowing traffic to flow continuously while administrative alerts trigger. Once Redis connectivity is restored, the distributed limiters resume normal operations without system downtime.

### Why `AtomicReference` with Compare-and-Set Retry Loops for Fallbacks

The local rate limiter fallback must handle multithreaded traffic safely without introducing thread blocking. Traditional `synchronized` code boundaries or `ReentrantLock` implementations would achieve thread safety but risk blocking Netty event loop threads if held during long execution loops.

Using an `AtomicReference<BucketState>` combined with a non-blocking compare-and-set retry loop avoids locking threads. The execution loop reads the memory reference, calculates token changes, and attempts an atomic data swap via compareAndSet. If a parallel thread updates the state reference mid-calculation, the swap fails safely, and the loop retries against the updated state. Under normal traffic conditions, this operation completes in a single iteration without blocking thread execution.

## Pipeline Integration & Filter Mechanics

### Why `ConcurrentHashMap` over Spring Cache for Local Storage

The Spring Cache abstraction provides a clean development experience using declarative annotations like `@Cacheable`. However, beneath its high-level interface, Spring Cache relies on synchronous, blocking mechanisms to manage entry states and verify TTL expirations. In a fully reactive pipeline running on Netty threads, any blocking operation can stall a thread tasked with handling thousands of parallel connections.

The gateway uses a manual `ConcurrentHashMap` paired with a `CachedContext<T>` wrapper that explicitly stores the target value alongside an absolute `expiryTime` timestamp. TTL verification is reduced to a non-blocking `System.currentTimeMillis() > expiryTime` comparison, while data updates are applied as a side effect using a non-blocking `ConcurrentHashMap.put()` operation inside a `doOnNext` block.

### Why Telemetry Collection Hooks into doFinally

The reactive `doOnSuccess` operator only triggers when a pipeline stream resolves successfully with a value, missing errors and client cancellations entirely. Conversely, `doOnError` only intercepts system faults, while doOnNext fires the moment data emits, which occurs before downstream response writing processes finish.

The pipeline binds telemetry collection to the `doFinally` operator, which guarantees execution upon any terminal signal (`ON_COMPLETE`, `ON_ERROR`, or `CANCEL`). Rate-limited rejections, standard successful completions, and client disconnections mid-flight all resolve through this step. This ensures every transaction records a log entry, and the tracked `durationMs` accurately measures the entire lifecycle from initial filter entry to connection teardown.

### Why `ServerHttpResponseDecorator` for Idempotency Capture

In a standard Spring WebFlux architecture, the response payload is processed as an active stream of raw `DataBuffer` chunks. The moment the `ProxyFilter` executes `response.writeWith(body)`, the byte stream flushes directly out to the network socket, leaving no native way to capture or read the payload afterward.

The gateway wraps the active response using a custom `ServerHttpResponseDecorator` to override the default `writeWith` logic. When downstream proxy elements trigger the write process, the execution passes through the decorator. The decorator intercepts the byte stream, writes a structured JSON log copy into Redis, and instantiates a new data buffer to pass the original payload out to the client socket via `super.writeWith()`.

```plaintext
ProxyFilter ──► ServerHttpResponseDecorator (Intercepts Chunks) ──► Cache to Redis
                                    │
                                    └──► super.writeWith() ──► Client Socket
```

To prevent memory leaks, the decorator explicitly releases the original data buffer allocations via `DataBufferUtils.release()` immediately after caching the payload. Skipping this step leads to rapid heap fragmentation and memory exhaustion that is difficult to diagnose.

### Why Idempotency Configurations are Opt-In Per Route

Enforcing universal idempotency validation across all mutating actions would require clients to append an `X-Idempotency-Key` header to every single `POST`, `PUT`, and `PATCH` request. For lightweight ingestion endpoints or analytics services that do not require deduplication, this design places an unnecessary integration burden on client applications.

Adding a `requires_idempotency` boolean property to the routing configuration allows engineering teams to target deduplication explicitly where it matters most, such as payment webhooks, order creation paths, or account provisioning forms. Standard ingestion paths process traffic directly, completely bypassing the memory overhead of Redis lock evaluations and response caching routines.

## Retrospective & Future Adjustments

**Control Plane and Data Plane Port Isolation:** Running the administrative Configuration API on the same port as the gateway filter chain via a `/management` path prefix was a pragmatic initial choice. In production environments, the data plane and control plane should operate on completely isolated network ports. Separating these interfaces prevents administrative configuration changes from conflicting with proxy routing logic and allows security teams to apply strict firewalls, restricting the control plane to internal company networks while leaving the data plane open to public traffic.

**Distributed Cache Invalidation Channels:** The current system clears local in-process caches by executing local invalidation loops upon receiving configuration updates. While functional for single-node setups, this design creates data drift across multi-instance gateway deployments. A write operation directed to a specific gateway instance clears its local memory cache, but adjacent gateway nodes will continue serving stale routing logic until their 30-second TTL expires. A production deployment requires an active Redis pub/sub invalidation channel to broadcast config changes to all gateway instances simultaneously.

**Tenant Lifecycle Cache Tuning:** Using the raw API key hash as the primary key for the `TenantContext` cache forces the gateway to index all routing configurations against the authentication token. A more scalable model would index routing details using a unique internal Tenant ID, maintaining a smaller, lightweight key-to-tenant map layer. This decoupling ensures that administrative operational events, such as rotating an API key, only clear the identity mapping table rather than invalidating the entire route cache structure.