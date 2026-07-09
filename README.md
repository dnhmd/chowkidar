# Chowkidar

> A self-hostable distributed rate limiting API gateway built to handle multi-tenant traffic at scale. Chowkidar sits in front of your services, enforces per-tenant rate limits using atomic Redis Lua scripts, and proxies allowed traffic to your upstream backends with per-route circuit breaker isolation.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7.2-red?style=flat-square&logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker)
![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-green?style=flat-square)

---

## What is Chowkidar?

Chowkidar is a production-grade API gateway featuring built-in distributed rate limiting. It serves as a self-hostable alternative to Kong or Tyk for engineering teams that require absolute control over their traffic enforcement layer.

Every incoming request passes through a non-blocking, reactive filter chain. The system resolves the specific tenant from an API key, executes two parallel rate limiting checks against Redis, and either routes the payload to the designated upstream backend or returns a 429 status code with accurate rate metrics. Built entirely on Spring WebFlux, the application executes asynchronously without stalling the underlying event loop threads.

The gateway applies two distinct evaluation strategies simultaneously. The token bucket algorithm manages velocity, smoothing out sharp traffic bursts by replenishing available tokens at a steady rate. Concurrently, a sliding window counter tracks total volume consumption over a rolling time window. Both strategies execute via Lua scripts on the Redis cluster, guaranteeing that the read-compute-write cycle remains atomic under heavy concurrent load across any number of active gateway nodes.

If the Redis tier suffers an outage, the gateway avoids a hard failure state. It transitions to an in-process local token bucket allocated per gateway instance, maintaining baseline perimeter protection until cluster connectivity stabilizes.

---

## Architecture

```
                     Client Request
                          |
                          v
          +-------------------------------+
          |       CHOWKIDAR GATEWAY       |
          |                               |
          |  1. ContextResolutionFilter   |
          |     - Validate X-API-Key      |
          |     - Load TenantContext      |
          |     - In-process cache (30s)  |
          |     - Cache miss -> Postgres  |
          |                               |
          |  2. RateLimiterFilter         |
          |     - Match route by path     |
          |     - Token Bucket (velocity) |---> Redis (Lua)
          |     - Sliding Window (volume) |---> Redis (Lua)
          |     - Redis down -> fallback  |---> Local JVM
          |     - Rejected -> 429         |
          |                               |
          |  3. ProxyFilter               |
          |     - Forward via WebClient   |---> Tenant Upstream
          |     - Per-route circuit       |
          |       breaker (Resilience4j)  |
          |                               |
          |  doFinally -> telemetry       |
          +-------------------------------+
                          |
                          v
                  Tenant Backend
```

### Project Structure

```
chowkidar/
├── gateway/                         # Spring Boot application
│   └── src/main/java/com/chowkidar/gateway/
│       ├── context/                 # Domain model and cache service
│       │   ├── model/               # Tenant, Route, TenantContext records
│       │   └── service/             # ContextService with in-process cache
│       ├── filter/                  # WebFilter chain
│       │   ├── ContextResolutionFilter.java
│       │   ├── RateLimiterFilter.java
│       │   └── ProxyFilter.java
│       ├── ratelimit/               # Rate limiting layer
│       │   ├── limiter/             # TokenBucket, SlidingWindow, Local
│       │   └── model/               # RateLimitResult
│       ├── persistence/             # R2DBC entities, repositories, mappers
│       ├── management/              # Config API (controllers, services, DTOs)
│       ├── exception/               # GlobalExceptionHandler, ErrorResponse
│       └── config/                  # InfrastructureConfig (Redis, WebClient)
├── docker-compose.yaml              # Postgres + Redis
└── echo_server.py                   # Local test upstream
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5 / Spring WebFlux |
| Architecture | Pipeline / Filter Chain |
| Database | PostgreSQL 16 (R2DBC) |
| Cache | Redis 7.2 (Lua scripts) |
| In-Process Cache | ConcurrentHashMap with manual TTL |
| Fault Tolerance | Resilience4j Circuit Breakers |
| Migrations | Flyway (JDBC) |
| Build Tool | Maven |
| Containerization | Docker Compose |

---

## How the Rate Limiting Works

Every incoming request triggers both evaluation algorithms concurrently using a reactive `Mono.zip` wrapper.

**Token Bucket (Velocity Control):** Every tenant route maps to a dedicated token bucket defined by a specific capacity and mathematical refill speed. Token balances update lazily on demand by measuring the exact duration since the previous request, avoiding background polling loops. When the bucket runs dry, traffic is deflected, neutralizing quick burst spikes.

**Sliding Window Counter (Volume Control):** This layer measures total usage caps over a shifting time window by tracking twin counter variables and evaluating them through a weighted mathematical formula. This mechanism maintains long-term volume enforcement, providing the data accuracy required for quotas or usage-based billing.

Both algorithms run inside Redis through custom Lua scripts. Because Redis evaluates Lua scripts in a single-threaded execution loop, the check-and-update sequence remains entirely atomic across the cluster, preventing race conditions or distributed locking delays.

When Redis is completely unreachable, validation tasks route to a local fallback mechanism that uses an `AtomicReference` compare-and-set sequence inside the JVM. While localized rather than distributed, this safety valve ensures the gateway remains operational under infrastructure degradation.

---

## Getting Started

### Prerequisites

- Docker and Docker Compose
- Java 21
- Maven 3.9+

### 1. Clone the repository

```bash
git clone https://github.com/dnhmd/chowkidar.git
cd chowkidar
```

### 2. Start infrastructure

```bash
docker compose up -d
```

This initializing step provisions PostgreSQL on port 5432 and Redis on port 6379. Flyway handles data schema updates automatically during application initialization.

### 3. Start the gateway

```bash
cd gateway
./mvnw spring-boot:run
```

The core gateway engine operates on port 8080. Migration verifications log directly to the console during initial application boot.

### 4. Start a test upstream

```bash
python3 echo_server.py
```

This launches a minimal Python HTTP echo server on port 8081 to mirror payload properties back as clean JSON for validation.

---

## Quick Start

### Create a tenant

```bash
curl -X POST http://localhost:8080/management/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "my-service"}'
```

```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service",
  "apiKey": "9f8ac6b4d8384fac843d09a77f1e27d5"
}
```

### Create a route

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

### Send a request through the gateway

```bash
curl http://localhost:8080/echo \
  -H "X-API-Key: 9f8ac6b4d8384fac843d09a77f1e27d5"
```

The proxy layer maps the incoming token, applies both rate limit algorithms, and forwards the payload to the echo backend. Outbound responses append `RateLimit-Limit`, `RateLimit-Remaining`, and `RateLimit-Reset` tracking headers automatically. Deflected traffic writes a standard `Retry-After` header alongside a 429 status code.

---

## Config API Reference

Administrative actions route through the `/management` prefix, bypassing the rate limiting engine entirely.

### Tenants

| Method | Path | Description |
|---|---|---|
| `POST` | `/management/tenants` | Provisions a new tenant profile and generates a unique API key. |
| `GET` | `/management/tenants` | Returns an index of all registered tenant profiles. |
| `GET` | `/management/tenants/{id}` | Fetches a target tenant record by its unique identifier. |
| `PATCH` | `/management/tenants/{id}` | Modifies an existing tenant's profile name. |
| `DELETE` | `/management/tenants/{id}` | Purges a tenant record from the system. |

### Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/management/tenants/{id}/routes` | Appends a route path to a target tenant profile. |
| `GET` | `/management/tenants/{id}/routes` | Indexes all active routes mapped to a target tenant. |
| `GET` | `/management/tenants/{id}/routes/{id}` | Fetches detailed route configurations by ID. |
| `PATCH` | `/management/tenants/{id}/routes/{id}/upstream` | Modifies the target upstream backend destination URL. |
| `PATCH` | `/management/tenants/{id}/routes/{id}/rate` | Updates the rate limiting parameters for a specific route path. |
| `DELETE` | `/management/tenants/{id}/routes/{id}` | Removes a specific route path from a tenant configuration. |

Omitted parameters default to standard configurations upon route creation:

| Field | Default | Description |
|---|---|---|
| `capacity` | 100 | Maximum capacity cap allocated to the token bucket. |
| `refillRate` | 10 | Token replenishment speed per single second interval. |
| `volumeLimit` | 10000 | Cumulative transaction cap per rolling window framework. |
| `windowSize` | 3600 | Absolute duration of the rolling window calculated in seconds. |

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `r2dbc:postgresql://localhost:5432/chowkidar_db` | Reactive R2DBC connection target details. |
| `DB_USERNAME` | `chowkidar_user` | Authorized administration database user account. |
| `DB_PASSWORD` | `chowkidar_password` | Access verification credential for the database schema. |
| `M_DB_URL` | `jdbc:postgresql://localhost:5432/chowkidar_db` | Standard JDBC driver target path allocated to Flyway migrations. |
| `REDIS_HOST` | `localhost` | Target network destination for the core cache cluster. |
| `REDIS_PORT` | `6379` | Operational network connection port for Redis routing. |

---

## Key Engineering Decisions

**End-to-End Reactive Processing:** The entire request path is built on Spring WebFlux, R2DBC data routines, and `ReactiveRedisTemplate`. This prevents blocking operations from stalling the Netty event loops. Telemetry events route via a decoupled `doFinally` hook, firing as a post-response side effect once client connections resolve.

**Atomic Evaluating via Redis Lua Scripts:** Rate calculation rules operate entirely inside custom Lua scripts. Because Redis evaluates Lua scripts within a single execution thread, the evaluation and update loops remain completely atomic across any number of parallel gateway nodes, removing the risk of synchronization race conditions.

**Key Space Separation per Route:** Token velocity state variables and rolling volume metrics are split across distinct Redis keys. Isolating these components prevents lock contention inside Redis, allowing parallel scripts to execute via `Mono.zip` without memory block delays.

**Manual Cache-Aside Implementations:** Standard Spring Cache abstractions use blocking components beneath the surface, making them dangerous for reactive runtimes. The profile resolution layer utilizes a plain `ConcurrentHashMap` backed by manual TTL checks to deliver a non-blocking, clean caching layout.

**Fail-Open Resilience Strategies:** A localized Redis cluster crash will not take down the gateway layer. The fallback rate limiter uses an `AtomicReference` structure operating a standard compare-and-set sequence to enforce thread-safe token bucket boundaries inside the local JVM while the cache cluster recovers.

**Isolated Upstream Circuit Boundaries:** Outbound destinations operate inside unique `upstream-{routeId}` circuit breaker instances. Separating these state boundaries ensures a failure in one tenant's upstream application can never affect adjacent tenant routing paths.

**Centralized Management Trapping:** Administrative configurations execute on a shared application port under a singular `/management` path prefix. A consolidated `GatewayPaths.isManagementPath()` helper function evaluates traffic at the entry line of all WebFilters, providing dry, unified path exclusions with zero logic duplication.

---

## Live Demo

> Deployment preparation is underway. Public platform endpoints will be updated here upon completion.

---

## Sprint History

| Sprint | Focus                                                                                                                                                                                                      | Status   |
|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| Sprint 1 | Reactive filter architecture, token bucket and sliding window Lua scripts, distributed Redis caching, WebClient proxy routing.                                                                             | Complete |
| Sprint 2 | Centralized Configuration API, isolated per-route circuit breakers, local JVM fallback limiters, global data validation and uniform exception responses.                                                   | Complete |
| Sprint 3 | API key protection, Distributed idempotency, Structured production logging, core Actuator metrics integration, end-to-end filter arrays, infrastructure failure testing, concurrent reactive benchmarking. | Planned  |
