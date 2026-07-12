package com.chowkidar.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class GatewayIntegrationTests {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" +
                        postgres.getFirstMappedPort() + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private WebTestClient webTestClient;

    private String validTenantId;
    private String validApiKey;
    private String validRouteId;

    @BeforeEach
    void setupFreshTenantAndRouteContext() {
        Map<String, String> tenantPayload = Map.of(
                "name", "Isolated Corp"
        );

        webTestClient.post()
                .uri("/management/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tenantPayload)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .consumeWith(result -> {
                    Map<String, Object> body = result.getResponseBody();
                    this.validTenantId = (String) body.get("id");
                    this.validApiKey = (String) body.get("apiKey");
                });

        Map<String, Object> routePayload = Map.of(
                "path", "/echo",
                "upstreamUrl", "http://127.0.0.1:8081",
                "capacity", 2,
                "refillRate", 1,
                "volumeLimit", 100,
                "windowSize", 60
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(routePayload)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .consumeWith(result -> {
                    Map<String, Object> body = result.getResponseBody();
                    this.validRouteId = (String) body.get("id");
                });
    }

    @Test
    void shouldCreateNewTenantSuccessfully() {
        Map<String, String> requestBody = Map.of(
                "name", "Random Corp"
        );

        webTestClient.post()
                .uri("/management/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.name").isEqualTo("Random Corp")
                .jsonPath("$.apiKey").exists();
    }

    @Test
    void shouldNotCreateNewTenantWithBlankName() {
        Map<String, String> requestBody = Map.of(
                "name", ""
        );

        webTestClient.post()
                .uri("/management/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldNotCreateNewTenantWithoutNameField() {
        Map<String, String> requestBody = Map.of();

        webTestClient.post()
                .uri("/management/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldCreateNewRouteSuccessfully() {
        Map<String, Object> customRoutePayload = Map.of(
                "path", "/custom-endpoint",
                "upstreamUrl", "http://127.0.0.1:8081",
                "capacity", 10,
                "refillRate", 2,
                "volumeLimit", 500,
                "windowSize", 30
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(customRoutePayload)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.path").isEqualTo("/custom-endpoint")
                .jsonPath("$.capacity").isEqualTo(10)
                .jsonPath("$.refillRate").isEqualTo(2);
    }

    @Test
    void shouldNotCreateNewRouteWithBlankPath() {
        Map<String, Object> customRoutePayload = Map.of(
                "path", "",
                "upstreamUrl", "http://127.0.0.1:8081",
                "capacity", 10,
                "refillRate", 2,
                "volumeLimit", 500,
                "windowSize", 30
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(customRoutePayload)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldNotCreateNewRouteWithBlankUpstreamUrl() {
        Map<String, Object> customRoutePayload = Map.of(
                "path", "/custom-endpoint",
                "upstreamUrl", "",
                "capacity", 10,
                "refillRate", 2,
                "volumeLimit", 500,
                "windowSize", 30
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(customRoutePayload)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldNotCreateNewRouteWithoutPath() {
        Map<String, Object> customRoutePayload = Map.of(
                "upstreamUrl", "http://127.0.0.1:8081",
                "capacity", 10,
                "refillRate", 2,
                "volumeLimit", 500,
                "windowSize", 30
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(customRoutePayload)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldNotCreateNewRouteWithoutUpstreamUrl() {
        Map<String, Object> customRoutePayload = Map.of(
                "path", "/custom-endpoint",
                "capacity", 10,
                "refillRate", 2,
                "volumeLimit", 500,
                "windowSize", 30
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(customRoutePayload)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldAllowAuthenticatedRequest() {
        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldReturnCachedResponseForDuplicateIdempotentRequest() {
        Map<String, Boolean> patchIdempotencyPayload = Map.of(
                "requiresIdempotency", true
        );

        webTestClient.patch()
                .uri("/management/tenants/{tenantId}/routes/{id}/idempotency", this.validTenantId, this.validRouteId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patchIdempotencyPayload)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requiresIdempotency").isEqualTo(true);

        String uniqueIdempotencyKey = java.util.UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .header("X-Idempotency-Key", uniqueIdempotencyKey)
                .exchange()
                .expectStatus().isOk();


        webTestClient.post()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .header("X-Idempotency-Key", uniqueIdempotencyKey)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Idempotent-Replay", "true");
    }

    @Test
    void shouldReturn429WhenRateLimitIsExhausted() {
        webTestClient.get().uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange().expectStatus().isOk();

        webTestClient.get().uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange().expectStatus().isOk();

        webTestClient.get().uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void shouldReturn401WhenApiKeyIsInvalid() {
        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", "completely-wrong-key-value")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn401WhenApiKeyIsMissing() {
        webTestClient.get()
                .uri("/echo")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn404WhenRouteIsUnmatched() {
        webTestClient.get()
                .uri("/wrong-url")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isNotFound();
    }
}
