# Chowkidar: Sprint 6 Summary

## Overview

Sprint 6 focused on making Chowkidar deployable, observable, and usable without touching a terminal. The sprint delivered a containerized multi-stage Docker build, a Railway cloud deployment with managed PostgreSQL and Redis, a Svelte admin portal served as static files from the gateway JAR, and Prometheus and Grafana observability integrated into the local development stack. By the end of Sprint 6, Chowkidar has a live public URL, a working admin portal, and structured metrics flowing into dashboards.

---

## Core Components

### 1. Multi-Stage Dockerfile

Prior to Sprint 6, the gateway had no Dockerfile. Deployment required a pre-built JAR, which meant the build environment had to be set up before Docker could do anything useful.

A multi-stage Dockerfile was introduced to make the build fully self-contained. The first stage uses a Maven image to compile the source and produce the JAR. The second stage copies only the JAR into a minimal JRE image. This eliminates any dependency on local Maven or JDK installations and produces a lean final image.

The Dockerfile lives at `gateway/Dockerfile` and is the sole artifact Railway uses for deployment. No `railway.toml` or build configuration is required beyond pointing Railway at the `gateway/` root directory.

### 2. Docker Compose Gateway Service

The existing `docker-compose.yaml` at the repo root was extended with a `gateway` service that builds from `./gateway` and depends on `postgres` and `redis`. All environment variables are passed explicitly, matching the `application.yaml` property resolution pattern. The gateway service joins the existing `chowkidar-network` bridge network, allowing container-to-container communication using container names as hostnames.

Local development now starts with a single command:

```bash
docker compose up -d
```

All three services start in dependency order. The gateway waits for Postgres and Redis before accepting connections.

### 3. Railway Cloud Deployment

Chowkidar is deployed on Railway at `https://chowkidar-production.up.railway.app`. The deployment stack consists of three Railway services: the gateway (built from the Dockerfile), a managed PostgreSQL 16 instance, and a managed Redis 8 instance.

Railway's variable reference syntax (`${{Postgres.PGHOST}}`) wires the managed database and cache credentials into the gateway environment at runtime. Redis on Railway requires password authentication — `spring.data.redis.password` was added to `application.yaml` to support the `REDIS_PASSWORD` environment variable, defaulting to empty for local development where Redis runs without a password.

The gateway serves the portal, the management API, and the proxy on a single port. Railway's managed SSL terminates HTTPS at the edge.

### 4. Admin Portal

The admin portal is a Svelte single-page application built with Vite and served as static files from `gateway/src/main/resources/static/`. Because it is embedded in the gateway JAR, no separate deployment or server is needed. Opening the gateway URL in a browser loads the portal directly.

**Portal architecture:**

The portal has two modes — operator mode and tenant mode — implemented as two routes in `svelte-spa-router` with hash-based navigation (`/#/` and `/#/tenant`).

Operator mode is unauthenticated. It shows a list of all tenants with create, edit, and delete operations. Creating a tenant displays the API key once in a modal with a copy-to-clipboard button. The key is never shown again.

Tenant mode requires an API key. Clicking Login on a tenant card prompts for the API key, which is validated against `POST /management/auth/validate`. On success, a session is written to `sessionStorage` with an 8-hour TTL. The session stores tenant ID, tenant name, API key, status, and deprecated flag. On subsequent visits, if the session is valid, the portal skips the login prompt and goes straight to tenant mode.

Tenant mode has four sections accessed via top tabs:

- **Dashboard**: reads `GET /management/tenants/{id}/routes/health` and displays a health summary with per-route UP/DOWN/UNKNOWN status badges and last-checked timestamps. A refresh button triggers a manual reload.
- **Routes**: full CRUD for routes with an inline edit modal that has four sub-tabs: URLs (upstream and fallback), Rate Limits (capacity, refill rate, volume limit, window size), Timeout, and Idempotency toggle.
- **IP Rules**: list, create, update action, and delete. Displays the current evaluation mode (blocklist or allowlist) based on whether any ALLOW rules exist. Each rule shows the IP address and action badge.
- **Settings**: rotate API key with a post-rotation modal that displays the new key once. Shows tenant ID, name, and current key status. Displays a deprecation warning banner if the session was authenticated with a rotated key still in its grace period.

**Design system:**

The portal uses a five-color palette: alabaster grey (`#eaeaea`) as background, jet black (`#183642`) and twilight indigo (`#313d5a`) as text, vintage lavender (`#73628a`) as the accent and interactive color, and periwinkle (`#cbc5ea`) for borders and subtle surfaces. Typography uses Playfair Display for headings, Inter for body and UI, and JetBrains Mono for API keys and code values. All spacing follows an 8px base unit.

**Build and deployment:**

The portal is built locally with `node node_modules/vite/bin/vite.js build`, which outputs to `gateway/src/main/resources/static/`. The built output is committed to the repository. Railway's multi-stage Dockerfile picks it up during the Maven build stage and packages it into the JAR. No separate build step is needed on Railway.

The Vite dev server proxies `/api` to `http://localhost:8080` during local development via `vite.config.mjs`, eliminating CORS issues. In production, the portal and gateway share the same origin so no proxy is needed.

### 5. Auth Validation Endpoint

A new `POST /management/auth/validate` endpoint was added to support the portal login flow. It accepts an API key, calls `ContextService.resolve()`, and returns tenant ID, tenant name, status, and deprecated flag. This reuses the full resolution logic including status checks and deprecated key detection, ensuring login behavior is consistent with proxy authentication behavior.

### 6. Static Resource Bypass

`GatewayPaths.shouldBypassFilters()` was extended with an `isFrontendPath()` check to allow static resources to be served without API key authentication. The check covers `/`, `/assets/**`, and files ending in `.html`, `.js`, `.css`, `.ico`, and `.svg`. Without this, the filter chain intercepted all requests to the portal and returned 401.

### 7. Prometheus and Grafana

`micrometer-registry-prometheus` was added to `pom.xml`. The `/actuator/prometheus` endpoint was added to the Actuator exposure list in `application.yaml`. Spring Boot's Micrometer integration automatically exposes JVM metrics, R2DBC connection pool metrics, Lettuce Redis command metrics, Resilience4j circuit breaker states, and HTTP server request statistics in Prometheus format.

Prometheus runs as a Docker service and scrapes `/actuator/prometheus` every 15 seconds. The Prometheus configuration is baked into a custom Docker image (`prometheus/Dockerfile`) rather than mounted as a file volume, working around a WSL2 Docker Desktop limitation that prevents direct file mounts.

Grafana connects to Prometheus as a data source and serves dashboards at `http://localhost:3000`. The JVM Micrometer dashboard (ID 11378) provides out-of-the-box visibility into heap usage, GC activity, thread counts, HTTP request rates, and R2DBC pool metrics.

---

## Architecture Choices

**Multi-stage Dockerfile over pre-built JAR:** Requiring a pre-built JAR means the CI/CD environment must have Java and Maven installed before Docker runs. A multi-stage build makes the Dockerfile the single source of truth for the build process. Railway, GitHub Actions, or any Docker-capable environment can build and deploy without additional setup.

**Portal served from gateway JAR over separate deployment:** A separate frontend deployment adds infrastructure complexity, a second service to manage, and cross-origin request handling. Serving the portal as static files from Spring Boot's `resources/static/` keeps the deployment footprint to a single service with a single URL. The tradeoff is that every portal change requires a gateway rebuild, which is acceptable for a self-hosted product at this scale.

**Baked Prometheus config over volume mount:** WSL2 Docker Desktop does not reliably support direct file mounts from the WSL filesystem. Baking the configuration into a custom Prometheus image via a `COPY` instruction in a Dockerfile sidesteps this entirely. The image is slightly larger but requires no host filesystem dependency.

**`sessionStorage` over `localStorage` for tenant sessions:** `sessionStorage` is cleared when the browser tab closes. `localStorage` persists until explicitly cleared. For a gateway admin portal where sessions carry API keys, `sessionStorage` is the safer default. The session ends when the operator closes the tab rather than persisting indefinitely across browser restarts.

**Hash-based routing over server-side routing:** The portal is served as static files with no server-side routing awareness. Hash-based routing (`/#/tenant`) handles navigation entirely in the browser without requiring the server to know about portal routes. The gateway only needs to serve `index.html` for the root path — all further navigation is client-side.

---

## Package Structure Changes

```plaintext
gateway/
├── Dockerfile                               # Multi-stage build
├── src/main/
│   ├── java/com/chowkidar/gateway/
│   │   ├── config/
│   │   │   └── GatewayPaths.java            # isFrontendPath() bypass added
│   │   └── management/
│   │       ├── controller/
│   │       │   └── AuthController.java      # POST /management/auth/validate
│   │       └── dto/
│   │           ├── request/
│   │           │   └── ValidateKeyRequest.java
│   │           └── response/
│   │               └── ValidateKeyResponse.java
│   └── resources/
│       ├── application.yaml                 # prometheus endpoint, redis password
│       └── static/                          # Built Svelte portal output

portal/
├── package.json
├── vite.config.mjs
├── svelte.config.js
├── index.html
└── src/
    ├── main.js
    ├── App.svelte
    ├── styles/
    │   └── global.css
    ├── lib/
    │   ├── api.js
    │   └── session.js
    ├── pages/
    │   ├── Home.svelte
    │   └── Tenant.svelte
    └── components/
        ├── Modal.svelte
        ├── TenantCard.svelte
        └── tenant/
            ├── Dashboard.svelte
            ├── Routes.svelte
            ├── IpRules.svelte
            └── Settings.svelte

prometheus/
├── Dockerfile
└── prometheus.yaml

docker-compose.yaml                          # gateway, prometheus, grafana services added
```

---

## Next Steps

- Integration test coverage for Sprint 5 and 6 features
- Custom Grafana dashboard for Chowkidar-specific metrics (429 rate, CB state, upstream latency)
- Prometheus and Grafana on Railway
- Control plane and data plane port isolation
- Redis pub/sub cache invalidation for multi-node deployments