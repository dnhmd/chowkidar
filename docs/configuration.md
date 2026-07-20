# Configuration

All configuration properties follow the uniform `${VARIABLE:default}` resolution pattern. The gateway application runs with sensible operational defaults out of the box. For production deployments, override these baselines using standard environment variables.

---

## Database Architecture

| Configuration Property       | Environment Variable | Default Value                                    | Description                                                                              |
|------------------------------|----------------------|--------------------------------------------------|------------------------------------------------------------------------------------------|
| `spring.r2dbc.url`           | `DB_URL`             | `r2dbc:postgresql://localhost:5432/chowkidar_db` | Asynchronous, reactive R2DBC connection string for the core gateway proxy runtime.       |
| `spring.r2dbc.username`      | `DB_USERNAME`        | `chowkidar_user`                                 | Primary runtime database credential username.                                            |
| `spring.r2dbc.password`      | `DB_PASSWORD`        | `chowkidar_password`                             | Primary runtime database credential password.                                            |
| `spring.datasource.url`      | `M_DB_URL`           | `jdbc:postgresql://localhost:5432/chowkidar_db`  | Blocking JDBC connection string used exclusively by Flyway schema migrations at startup. |
| `spring.datasource.username` | `DB_USERNAME`        | `chowkidar_user`                                 | Migration execution database credential username.                                        |
| `spring.datasource.password` | `DB_PASSWORD`        | `chowkidar_password`                             | Migration execution database credential password.                                        |

**Structural Note:** The architecture separates database connection infrastructure into discrete pools. Flyway requires a traditional blocking JDBC driver to execute schema mutations sequentially during the application initialization phase, while the active gateway runtime leverages non-blocking R2DBC drivers. Both parameters can map to the same physical database instance but must be provisioned independently.

## Distributed Cache Tier (Redis)

| Configuration Property      | Environment Variable | Default Value | Description                                                         |
|-----------------------------|----------------------|---------------|---------------------------------------------------------------------|
| `spring.data.redis.host`    | `REDIS_HOST`         | `localhost`   | Target Redis cluster hostname.                                      |
| `spring.data.redis.port`    | `REDIS_PORT`         | `6379`        | Active Redis network port.                                          |
| `spring.data.redis.timeout` | —                    | `60000`       | Asynchronous network connection timeout calculated in milliseconds. |

## Cryptographic Security

| Configuration Property                          | Environment Variable  | Default Value                                   | Description                                                                                                                                                                                                          |
|-------------------------------------------------|-----------------------|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `chowkidar.security.hmac-secret`                | `HMAC_SECRET`         | `chowkidar-default-secret-change-in-production` | Server-side signature token used to calculate HMAC-SHA256 API key hashes.                                                                                                                                            |
| `chowkidar.security.api-key-grace-period-hours` | `API_KEY_GRACE_HOURS` | `12`                                            | Duration in hours that a rotated key remains valid after a new key is issued. Callers using a key within this window receive an `X-Api-Key-Deprecated: true` response header. After expiry, the old key returns 403. |

**Security Warning:** The out-of-the-box secret value is a plain text placeholder meant only for local evaluation. In staging or production environments, map HMAC_SECRET to a cryptographically random string of at least 32 characters. Because all active API key database validations match against this signature, changing this secret will instantly invalidate all existing tenant tokens.
**Operational Note:** Align this value with your deployment and notification cadence. 12 hours gives service teams one business half-day to propagate a new key. Shortening this window increases security but raises the risk of service disruption if callers are slow to rotate.

## In-Process Memory Cache

| Configuration Property   | Default Value | Description                                                                                |
|--------------------------|---------------|--------------------------------------------------------------------------------------------|
| `chowkidar.cache.ttl-ms` | `30000`       | Lifetime duration in milliseconds for local in-process `TenantContext` memory allocations. |

Tuning this property changes performance characteristics: lowering the TTL value makes remote administrative configuration adjustments visible to the proxy filters more quickly but increases read pressure on Postgres. Conversely, extending this window minimizes database lookup overhead but prolongs the duration where stale configuration matrices are served following a Management API write.

## Baseline Rate Limiting Metrics

These properties establish the default traffic enforcement matrix applied when a route path is initialized without explicit custom rate metrics.

| Configuration Property                      | Default Value | Description                                                                |
|---------------------------------------------|---------------|----------------------------------------------------------------------------|
| `chowkidar.rate-limit.default-capacity`     | `100`         | Maximum token depth for the short-term burst token bucket limiter.         |
| `chowkidar.rate-limit.default-refill-rate`  | `10`          | Frequency of tokens replenished per second to throttle sustained velocity. |
| `chowkidar.rate-limit.default-volume-limit` | `10000`       | Absolute transaction ceiling per rolling volume window.                    |
| `chowkidar.rate-limit.default-window-size`  | `3600`        | Shifting volume execution window duration measured in seconds.             |

## Exact-Once Idempotency

| Configuration Property                   | Default Value | Description                                                                        |
|------------------------------------------|---------------|------------------------------------------------------------------------------------|
| `chowkidar.idempotency.ttl-minutes`      | `30`          | Retention lifetime in minutes for successful reponse payloads cached within Redis. |
| `chowkidar.idempotency.lock-ttl-seconds` | `30`          | Lifespan in seconds for an active transaction's concurrent `PROCESSING` lock flag. |

**Engineering Alignment:** Match the `lock-ttl-seconds` property to exceed your slowest upstream backend's maximum expected processing latency. If a target backend requires 25 seconds to complete a transaction and the lock expires after 30 seconds, concurrent duplicates hitting the gateway within that 25-second window will receive a clean 409 Conflict rejection. If the lock expires before the backend finishes processing, a subsequent duplicate request will bypass the idempotency layer, creating an accidental duplicate execution.

## Route Timeout

| Configuration Property               | Environment Variable       | Default Value | Description                                                                                            |
|--------------------------------------|----------------------------|---------------|--------------------------------------------------------------------------------------------------------|
| `chowkidar.route.default-timeout-ms` | `ROUTE_DEFAULT_TIMEOUT_MS` | `3000`        | Default upstream request timeout in milliseconds applied to routes that do not specify a custom value. |

## Slow Request Detection

| Configuration Property                      | Environment Variable        | Default Value | Description                                                                                                   |
|---------------------------------------------|-----------------------------|---------------|---------------------------------------------------------------------------------------------------------------|
| `chowkidar.proxy.slow-request-threshold-ms` | `SLOW_REQUEST_THRESHOLD_MS` | `2000`        | Requests exceeding this duration in milliseconds emit a structured WARN log event for operator investigation. |

## Health Check Scheduler

| Configuration Property               | Default Value | Description                                                            |
|--------------------------------------|---------------|------------------------------------------------------------------------|
| `chowkidar.health.interval-ms`       | `30000`       | Probe interval in milliseconds. All routes are probed on this cadence. |
| `chowkidar.health.timeout-ms`        | `3000`        | Maximum wait time per probe before marking the result as a failure.    |
| `chowkidar.health.concurrency-limit` | `10`          | Maximum number of concurrent probes across all routes per tick.        |
| `chowkidar.health.failure-threshold` | `3`           | Number of consecutive failed probes before a route is marked DOWN.     |

## IP Filter Cache

| Configuration Property             | Default Value | Description                                                                                                                                                       |
|------------------------------------|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `chowkidar.ip-filter.cache-ttl-ms` | `1800000`     | TTL in milliseconds for cached IP rule decisions in Redis. Defaults to 30 minutes. Cache entries are invalidated immediately on rule mutations regardless of TTL. |

## Fault Tolerance (Circuit Breakers)

Resilience boundaries are declared as named target instances under the central `resilience4j.circuitbreaker.instances` configuration node.

### Relational Database Protection Profile (Postgres)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      postgres:
        failure-rate-threshold: 50
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 3
        sliding-window-size: 20
        sliding-window-type: COUNT_BASED
        slow-call-rate-threshold: 80
        slow-call-duration-threshold: 2s
```

The Postgres circuit breaker trips open if 50% of executions within a 20-call sliding window fail or exceed a 2-second timeout boundary. Once tripped, the breaker isolates the database for 60 seconds before passing 3 trial transactions in a half-open state. This longer recovery window reflects the reality that relational database state stabilization takes more time than an individual upstream microservice recovery.

### Reverse Proxy Protection Profile (Upstream)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      upstream:
        failure-rate-threshold: 60
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 5
        sliding-window-size: 20
        sliding-window-type: COUNT_BASED
        slow-call-rate-threshold: 80
        slow-call-duration-threshold: 5s
```

The upstream proxy circuit breaker uses a more tolerant threshold (60% failure rate) and a faster recovery window (30 seconds). External network connections drop and recover quickly, so shorter isolation blocks prevent false positives from unnecessarily blocking tenant traffic.

**Dynamic Isolation:** Upstream circuit breakers instantiate dynamically per route using the `upstream-{routeId}` naming pattern. The configuration above serves as the master structural template applied to every individual route circuit initialized at runtime.

## Log Output Management

Log patterns and delivery modes are profile-controlled using the underlying `logback-spring.xml` configuration.

| Runtime Profile | Log Delivery Characteristics                                                                                         |
|-----------------|----------------------------------------------------------------------------------------------------------------------|
| `default`       | Emits both clean plain-text strings and structured JSON payloads directly to the console stream.                     |
| `prod`          | Emits high-speed structured JSON payloads to console, while rolling a physical log file at `./logs/application.log`. |

The rolling file appender splits logs at 10MB increments, maintains a 30-day historical window, and caps global file size storage at 2GB. Declare the target runtime environment profile using `spring.profiles.active` inside `application.yaml` or by passing the `SPRING_PROFILES_ACTIVE` environment variable.

## System Observability (Actuator)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, circuitbreakers
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
```

All system metrics and health diagnostics sit at the `/actuator/*` endpoint path and bypass the core gateway filters via the central `GatewayPaths.isActuatorPath()` utility check.

---

## Infrastructure Provisioning (Docker Compose)

The `docker-compose.yaml` blueprint located at the repository root spins up pre-configured PostgreSQL 16 and Redis 7.2 test containers using credentials that match the local development configuration templates.

```bash
docker compose up -d    # Provisions and launches the database containers in detached mode.
docker compose down     # Terminates container runtimes while preserving data inside named volumes.
docker compose down -v  # sDestroys container runtimes and completely wipes all named data volumes.
```

Data persists across standard container restarts using the named system volumes `postgres_data` and `redis_data`. Clear these volumes explicitly when you need to reset the gateway back to a clean state.
