# API Reference

All management endpoints live under the `/management` prefix and completely bypass the gateway filter chain. Public proxy traffic routes through standard system paths and requires a valid security token.

Internal monitoring and telemetry diagnostics live under `/actuator` and are also exempt from filter validations.

---

## Authentication

Proxy requests require an `X-API-Key` request header containing the raw token provisioned during tenant initialization. The gateway hashes this token at rest using HMAC-SHA256 before verification; raw access keys are shown exactly once upon generation and are never stored in plain text.

Administrative configuration endpoints do not require authentication headers.

Callers using a key that has been rotated but is still within its grace period will receive responses with an `X-Api-Key-Deprecated: true` header. This signals that the key will stop working after the grace window closes and a new key should be provisioned immediately.

| Header                 | Description                                                                                          |
|------------------------|------------------------------------------------------------------------------------------------------|
| `X-Api-Key-Deprecated` | Present and `true` when the request authenticated using a rotated key still within its grace period. |

---

## Tenants

### Create Tenant

```
POST /management/tenants
```

Provisions a new tenant database record and generates a unique API gateway key. The `apiKey` property returned in the response payload represents the only time the raw token is visible on the platform.

**Request Payload:**
```json
{
  "name": "my-service"
}
```

**Response (201 Created):**
```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service",
  "apiKey": "9f8ac6b4d8384fac843d09a77f1e27d5"
}
```

**Validation Constraints:** The `name` parameter must not be empty or blank.

### List Tenants

```
GET /management/tenants
```

Returns an index of all registered tenant profiles. Security tokens are omitted from listing or profile detail payloads.

**Response (200 OK):**
```json
[
  {
    "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
    "name": "my-service"
  }
]
```

### Get Tenant

```
GET /management/tenants/{id}
```

Fetches a specific tenant profile by its unique database identifier.

**Response (200 OK):**
```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service"
}
```

**Exception Rules:** Returns a 404 Not Found error if the provided identifier does not map to an active tenant.

### Update Tenant

```
PATCH /management/tenants/{id}
```

Modifies a tenant's display name. This parameter represents the only mutable property on a tenant profile; access tokens are immutable after creation.

**Request Payload:**
```json
{
  "name": "my-service-renamed"
}
```

**Response (200 OK):**
```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service-renamed"
}
```

**Pipeline Side Effects:** Triggers an immediate cache-aside invalidation loop for this tenant across the in-process cache tier to ensure name updates apply instantly.

### Rotate API Key

```
POST /management/tenants/{id}/rotate-key
```

Generates a new API key for the specified tenant. The previous key remains valid for a configurable grace period (default 12 hours) to allow callers time to propagate the new credential. After the grace window closes, the old key returns 403.

The `apiKey` property in the response represents the only time the new raw token is visible. It is not stored in plaintext and cannot be retrieved after this response.

**Response (200 OK):**
```json
{
  "id": "16c84d44-943e-444e-abaf-4c40bfeafa57",
  "name": "my-service",
  "apiKey": "3a7fc2e1b9264dac951e08b66f3a27c8"
}
```

**Exception Rules:** Returns 404 if the tenant does not exist.

**Pipeline Side Effects:** Invalidates the in-process cache entry for the old key hash immediately. Callers still using the old key within the grace period will trigger a fresh database lookup on the next request. Responses to deprecated key requests include `X-Api-Key-Deprecated: true`.

### Delete Tenant

```
DELETE /management/tenants/{id}
```

Removes a tenant profile and cascades deletions down to all associated route tables at the relational database tier.

**Response (204 No Content)**

**Pipeline Side Effects:** Purges the tenant's context configurations from the local in-process cache map.

---

## Routes

Routes scope explicitly to a specific parent tenant. The `path` string serves as a structural component for Redis rate keys and is immutable after provisioning. To alter an operational route path, the existing entry must be purged and recreated to avoid orphaning token state indices in Redis.

### Create Route

```
POST /management/tenants/{tenantId}/routes
```

**Request Payload:**
```json
{
  "path": "/echo",
  "upstreamUrl": "http://your-service:8080",
  "capacity": 100,
  "refillRate": 10,
  "volumeLimit": 10000,
  "windowSize": 3600,
  "requiresIdempotency": false
}
```

Rate control metrics default to sensible platform values if omitted during route configuration setup:

| Field | Default | Description                                                       |
|---|---|-------------------------------------------------------------------|
| `capacity` | 100 | Token bucket maximum depth, bounds rapid traffic bursts.          |
| `refillRate` | 10 | Tokens added per single second interval, sets sustained velocity. |
| `volumeLimit` | 10000 | Absolute request execution ceiling per active rolling window.              |
| `windowSize` | 3600 | Duration of the shifting consumption window calculated in seconds.                               |
| `requiresIdempotency` | false | Enforces structural `X-Idempotency-Key` validation on incoming mutating payloads.       |

**Response (201 Created):**
```json
{
  "id": "ea7dcf9d-d870-460e-a8a5-ba7295ef7a1e",
  "path": "/echo",
  "upstreamUrl": "http://your-service:8080",
  "capacity": 100,
  "refillRate": 10,
  "volumeLimit": 10000,
  "windowSize": 3600,
  "requiresIdempotency": false
}
```

**Exception Rules:** Returns 404 if the target parent tenant does not exist, and 400 if the `path` or `upstreamUrl` string blocks contain empty variables.

**Pipeline Side Effects:** Clears the corresponding in-process cache map entry, exposing the new route path to active gateway traffic flows instantly.

### List Routes

```
GET /management/tenants/{tenantId}/routes
```

Returns an index of all configured routing entries scoped under a target parent tenant.

**Response (200 OK):**
```json
[
  {
    "id": "ea7dcf9d-d870-460e-a8a5-ba7295ef7a1e",
    "path": "/echo",
    "upstreamUrl": "http://your-service:8080",
    "capacity": 100,
    "refillRate": 10,
    "volumeLimit": 10000,
    "windowSize": 3600,
    "requiresIdempotency": false
  }
]
```

### Get Route

```
GET /management/tenants/{tenantId}/routes/{routeId}
```

Fetches details for a specific route configuration.

**Response (200 OK):** Returns a single route data model object matching the structure above.

**Exception Rules**: Emits a 404 error if either the parent tenant profile or the specified route entry is missing.

### Update Upstream URL

```
PATCH /management/tenants/{tenantId}/routes/{routeId}/upstream
```

Modifies the reverse proxy target destination URL for a specified route path. This configuration change is safe to execute at runtime because destination values are evaluated dynamically at proxy resolution time rather than being baked into Redis key states.

**Request Payload:**
```json
{
  "upstreamUrl": "http://new-service:8080"
}
```

**Response (200 OK)**: Returns the fully updated route data model object.

**Pipeline Side Effects**: Triggers a cache invalidation request across the gateway's internal memory maps.

### Update Rate Limits

```
PATCH /management/tenants/{tenantId}/routes/{routeId}/rate
```

Alters all four rate limiting execution criteria simultaneously. Updates reflect across incoming traffic flows as soon as the in-process cache clears (within a maximum 30-second window, or immediately if the local memory map has expired naturally).

This modification updates the core parameters stored inside PostgreSQL. Existing token balances and tracking frames stored inside Redis are intentionally preserved; if capacity values decline, the gateway script computes the corrected token metrics based on the new boundaries during the subsequent lazy refill transaction.

**Request Payload:**
```json
{
  "capacity": 50,
  "refillRate": 5,
  "volumeLimit": 5000,
  "windowSize": 1800
}
```

**Response (200 OK)**: Returns the fully updated route data model object.

### Update Idempotency Setting

```
PATCH /management/tenants/{tenantId}/routes/{routeId}/idempotency
```

Toggles active idempotency verification checks on or off for a chosen path. When enabled, all matching `POST`, `PUT`, and `PATCH` executions must supply a valid `X-Idempotency-Key` tracking header or face an immediate 400 Bad Request rejection.

**Request Payload:**
```json
{
  "requiresIdempotency": true
}
```

**Response (200 OK)**: Returns the fully updated route data model object.

### Delete Route

```
DELETE /management/tenants/{tenantId}/routes/{routeId}
```

Removes a route path from a tenant's profile configuration.

**Response (204 No Content)**

**Pipeline Side Effects:** Drops the active routing details from the internal memory cache layer. Active tracking limits and window footprints preserved inside Redis are not aggressively purged; they expire naturally based on the TTL windows applied during the final Lua evaluation loop.

---

## Gateway Proxy Traffic

To interact with target services, route client requests directly through the gateway network endpoint with the appropriate credentials appended.

```
GET /echo HTTP/1.1
Host: gateway.local:8080
X-API-Key: 9f8ac6b4d8384fac843d09a77f1e27d5
```

### Protocol Response Headers

Every payload returned via the gateway routing engine includes the following standard headers:

| Header                | Description                                                             |
|-----------------------|-------------------------------------------------------------------------|
| `RateLimit-Limit`     | The maximum capacity ceiling configured for the active route path.      |
| `RateLimit-Remaining` | Available transaction room remaining within the current token window.   |
| `RateLimit-Reset`     | Absolute time remaining in seconds until the active limit frame resets. |

Requests that drop into a 429 execution state append an additional back-off metric:

| Header        | Description                                                                   |
|---------------|-------------------------------------------------------------------------------|
| `Retry-After` | The exact cooldown duration in seconds a client must observe before retrying. |

Deduplicated responses served directly from the gateway caching tier append a tracking flag:

| Header                | Description                                                                           |
|-----------------------|---------------------------------------------------------------------------------------|
| `X-Idempotent-Replay` | Evaluates to `true`, confirms the payload was returned directly from the Redis layer. |

### Processing Idempotence

For routes configured with `requiresIdempotency: true`, append a unique tracking value to handle safe mutation tasks:

```
POST /payments HTTP/1.1
Host: gateway.local:8080
X-API-Key: 9f8ac6b4d8384fac843d09a77f1e27d5
X-Idempotency-Key: order-12345-attempt-1
```

If a network interruption occurs, retry the interaction using the identical header value. The gateway bypasses backend processing and delivers the exact response compiled during the initial successful transaction block. Cached payloads expire from the memory layer after 30 minutes.

---

## Error Handling Standards

All system exceptions and validation rejections return a unified JSON layout:

```json
{
  "timestamp": "2026-07-09T11:07:01.725858209Z",
  "path": "/echo",
  "status": 404,
  "error": "Not Found",
  "message": "Route not found",
  "requestId": "2225efd3-4"
}
```

| HTTP Status             | Triggering Platform Condition                                                                          |
|-------------------------|--------------------------------------------------------------------------------------------------------|
| 400 Bad Request         | Request data validation error or missing an expected X-Idempotency-Key header.                         |
| 401 Unauthorized        | The `X-API-Key` tracker is missing or failed cryptographic validation checks.                          |
| 404 Not Found           | No matching route path is registered for the incoming request line.                                    |
| 409 Conflict            | A concurrent idempotent execution block using the same key is already running.                         |
| 429 Too Many Requests   | Consumption thresholds have been breached across velocity or volume boundaries.                        |
| 503 Service Unavailable | Target upstream backend application is unresponsive or relational databases are offline.               |
| 403 Forbidden           | The tenant account is explicitly revoked, or the presented API key has passed its grace period expiry. |

---

## Health Analytics & System Metrics

```
GET /actuator/health
GET /actuator/metrics
GET /actuator/metrics/{metric-name}
```

The health check interface aggregates real-time statuses for the PostgreSQL connection pool, active Redis cluster nodes, local hardware disk space metrics, and individual Resilience4j circuit breaker state changes.

The metrics endpoint exposes more than 50 performance parameters, including granular telemetry details for `resilience4j.circuitbreaker.*` state maps, `r2dbc.pool.*` database connection numbers, `lettuce.command.*` cache performance values, and active `http.server.requests` throughput counts.