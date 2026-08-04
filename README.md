# Chowkidar

A self-hosted API gateway that gives small engineering teams production-grade traffic controls without enterprise-grade operational complexity.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7.2-red?style=flat-square&logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)
![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-green?style=flat-square)
![Live Demo](https://img.shields.io/badge/Live%20Demo-Railway-purple?style=flat-square)

**[Live Demo](https://chowkidar-production.up.railway.app)**

---

## The Problem

Most internal APIs start simple. Then requirements pile up.

> "We need API keys."  
> "We need rate limiting."  
> "Customers keep retrying payments."  
> "We should block abusive IPs."  
> "This service keeps timing out and taking everything down with it."

Teams end up rebuilding the same concerns inside every microservice, or adopting an enterprise gateway that is significantly more complex than their actual needs.

Chowkidar centralizes those concerns into one lightweight gateway that a small team can deploy, understand, and modify without becoming gateway specialists.

---

## Who This Is For

Consider Sarah. She is the only backend engineer at a startup with six developers. They have five services running in Docker. Customers are beginning to consume their APIs directly. Suddenly the team needs API keys, rate limiting, payment idempotency, IP allowlists, and basic resiliency when downstream services fail. They don't have a platform team, a Kubernetes cluster, or time to operate an enterprise gateway. They want one service they can understand and extend themselves.

Chowkidar is designed for:

- Solo developers and small startups
- SaaS teams with roughly 2 to 15 engineers
- Teams self-hosting their infrastructure
- Engineers comfortable with Docker and REST APIs who don't want to become gateway specialists

---

## Why Not Kong, Traefik, or Nginx?

**Kong** is an excellent enterprise gateway with plugins, clustering, authentication providers, service mesh integration, Kubernetes support, and much more. If your organization needs those capabilities, you should probably use Kong. Chowkidar exists for teams that don't.

**Traefik** shines in modern container environments where automatic service discovery is the primary requirement. If your problem is dynamic routing inside Kubernetes or Docker Swarm, Traefik is the better fit. Chowkidar focuses on API governance: distributed rate limiting, idempotency, API key lifecycle management, and tenant isolation.

**Nginx** is one of the fastest reverse proxies available. If all you need is routing and basic rate limiting, Nginx is a great choice. Chowkidar targets the point where configuration starts becoming application logic. Instead of composing Lua scripts, custom modules, and multiple configuration files, operators configure gateway behavior through a management API and an admin portal.

Chowkidar is not trying to replace mature gateways. It exists for teams that need production API controls without adopting an entire gateway platform.

---

## What It Does

Every request flows through a non-blocking reactive filter chain:

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
          |  2. IpFilterFilter                |
          |     Allowlist / blocklist check   |
          |     Redis-cached decision         |
          |                                   |
          |  3. RateLimiterFilter             |
          |     Token Bucket  -> Redis (Lua)  |
          |     Sliding Window -> Redis (Lua) |
          |     Redis down -> Local JVM       |
          |     Rejected -> 429               |
          |                                   |
          |  4. IdempotencyFilter             |
          |     Distributed lock via Redis    |
          |     Replay cached response        |
          |     Conflict -> 409               |
          |                                   |
          |  5. ProxyFilter                   |
          |     Forward via WebClient         |
          |     Per-route timeout             |
          |     Fallback URL on CB open       |
          |     Per-route circuit breaker     |
          +-----------------------------------+
                          |
                          v
                  Tenant Upstream Service
```

**Key capabilities:**

- **Dual-algorithm rate limiting**: token bucket and sliding window run in parallel via atomic Redis Lua scripts. Two algorithms because one isn't enough: token bucket stops short traffic spikes, sliding window enforces rolling quotas.
- **API key lifecycle management**: HMAC-SHA256 hashed keys, rotation with configurable grace periods, deprecated key warnings, explicit tenant revocation.
- **Distributed idempotency**: exactly-once execution for mutation endpoints using Redis `SET NX` atomic locking and response replay.
- **Per-route resilience**: configurable timeouts, fallback URLs with dedicated circuit breakers, upstream health check scheduler with state-change logging.
- **IP allowlist and blocklist**: per-tenant, Redis-cached, with allowlist mode activating automatically when any ALLOW rule exists.
- **Fail-open on Redis failure**: local JVM token bucket activates transparently when Redis is unreachable, keeping traffic moving.
- **Admin portal**: tenant management, route configuration, IP rules, key rotation, and route health dashboard. No curl commands required.
- **Structured observability**: JSON logs via logstash-logback-encoder, Prometheus metrics via Micrometer, Grafana dashboards, Spring Actuator health endpoints.

---

## Quick Start

**Prerequisites:** Docker, Docker Compose

```bash
git clone https://github.com/dnhmd/chowkidar.git
cd chowkidar
docker compose up -d
```

Open `http://localhost:8080`. The admin portal is running out of the box.

1. Create a tenant, copy the API key shown once on creation
2. Login as that tenant
3. Create a route pointing at your upstream service
4. Send requests through the gateway with `X-API-Key: {your-key}`

```bash
curl http://localhost:8080/your-path \
  -H "X-API-Key: your-api-key"
```

Every response includes rate limit headers:

```
RateLimit-Limit: 100
RateLimit-Remaining: 99
RateLimit-Reset: 3600
```

---

## Load Test Results

Tested against the full filter chain: HMAC validation, dual rate limiting via Redis Lua scripts, and WebClient proxying, on a single WSL2 development machine sharing CPU and memory with the gateway, Redis, PostgreSQL, and the upstream echo server.

| Metric | Gateway Routing Test | Rate Limit Flood Test |
|---|---|---|
| Sustained Throughput | 1,160 req/sec (200 VUs, 3m) | 1,118 req/sec (100 VUs, 1m) |
| Median Latency (p50) | 95ms | 67ms |
| p95 Latency | 277ms | 207ms |
| p99 Latency | 384ms | 365ms |
| Failure Rate | 0.00% | 0.00% |

Production deployment with isolated infrastructure would yield significantly higher throughput and lower tail latency.

---

## Engineering Trade-offs

Every non-obvious decision in this codebase has an explicit rationale. A few highlights:

**HMAC-SHA256 over BCrypt for API keys**: BCrypt's random salt makes deterministic database lookup impossible. A gateway needs to find a tenant by key hash on every request. HMAC with a server secret produces consistent output while keeping the database useless without the secret.

**Two separate Redis keys for rate limiting**: consolidating token bucket and sliding window state into one key forces sequential Lua script execution. Separate keys allow both scripts to run concurrently via `Mono.zip` without competing for the same lock.

**Fail-open on Redis failure**: a fail-closed posture turns a Redis blip into a complete tenant outage. The local JVM fallback absorbs the gap. A Denial of Service attack against the cache tier doesn't bring down the services behind the gateway.

**Idempotency as opt-in per route**: forcing idempotency on all mutating endpoints would break lightweight ingestion APIs that don't need deduplication. The `requiresIdempotency` flag gives operators explicit control.

**Reactor context for request-scoped state**: deprecated key flags and matched route data travel downstream via Reactor context, not method parameters or domain model fields. Domain objects carry no request-scoped state.

Full decision log in [Engineering Decisions](/docs/engineering-decisions.md).

---

## Documentation

- [Architecture](/docs/architecture.md): filter chain mechanics, state management, and design evolution across sprints
- [Engineering Decisions](/docs/engineering-decisions.md): trade-off analysis for every non-obvious choice
- [API Reference](/docs/api-reference.md): full management API endpoint documentation
- [Configuration](/docs/configuration.md): all environment variables and defaults

---

## Build History

| Sprint | Focus | Status    |
|---|---|-----------|
| Sprint 1 | Reactive filter chain, dual-algorithm rate limiting via Redis Lua scripts, WebClient proxy | Complete  |
| Sprint 2 | Management API, per-route circuit breakers, local JVM rate limit fallback, global exception handling | Complete  |
| Sprint 3 | HMAC API key hashing, distributed idempotency, structured logging, Testcontainers integration tests, k6 load tests | Complete  |
| Sprint 4 | API key rotation with grace periods, tenant revocation, deprecated key detection, structured logging pass | Complete  |
| Sprint 5 | Per-route timeouts, fallback URL routing, upstream health check scheduler, slow request detection, IP allowlist/blocklist | Complete  |
| Sprint 6 | Admin portal (Svelte), Dockerfile, Railway deployment, Prometheus + Grafana observability | Completed |