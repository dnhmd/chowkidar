package com.chowkidar.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ContextConfiguration(initializers = GatewayFailureScenarioTests.Initializer.class)
public class GatewayFailureScenarioTests {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            redis = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

            postgres.start();
            redis.start();

            MapPropertySource testcontainerProperties = new MapPropertySource("dynamic-container-ports", Map.ofEntries(
                    Map.entry("spring.flyway.url", postgres.getJdbcUrl()),
                    Map.entry("spring.flyway.user", postgres.getUsername()),
                    Map.entry("spring.flyway.password", postgres.getPassword()),
                    Map.entry("spring.datasource.url", postgres.getJdbcUrl()),
                    Map.entry("spring.datasource.username", postgres.getUsername()),
                    Map.entry("spring.datasource.password", postgres.getPassword()),
                    Map.entry("spring.r2dbc.url", "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + postgres.getDatabaseName()),
                    Map.entry("spring.r2dbc.username", postgres.getUsername()),
                    Map.entry("spring.r2dbc.password", postgres.getPassword()),
                    Map.entry("spring.data.redis.host", redis.getHost()),
                    Map.entry("spring.data.redis.port", redis.getFirstMappedPort()),
                    Map.entry("spring.data.redis.timeout", "1000ms"),
                    Map.entry("spring.data.redis.connect-timeout", "1000ms"),
                    Map.entry("chowkidar.cache.ttl-ms", "1000"),
                    Map.entry("resilience4j.circuitbreaker.instances.postgres.minimum-number-of-calls", "1"),
                    Map.entry("resilience4j.circuitbreaker.instances.postgres.failure-rate-threshold", "1")
            ));

            applicationContext.getEnvironment().getPropertySources().addFirst(testcontainerProperties);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setupWebTestClient() {
        webTestClient = webTestClient.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private String validTenantId;
    private String validApiKey;

    @BeforeEach
    void setupContext() {
        Map<String, String> tenantPayload = Map.of("name", "Resilient Corp");
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
                "capacity", 10,
                "refillRate", 1,
                "volumeLimit", 100,
                "windowSize", 60
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(routePayload)
                .exchange()
                .expectStatus().isCreated();
    }

    @AfterEach
    void tearDownContainers() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
        if (redis != null && redis.isRunning()) {
            redis.stop();
        }
    }

    @Test
    @DirtiesContext
    void shouldFallbackToLocalRateLimiterWhenRedisIsDown() {
        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isOk();

        redis.stop();

        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DirtiesContext
    void shouldFailGracefullyWith503AfterCacheExpiresAndPostgresIsDown() throws InterruptedException {
        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isOk();

        postgres.stop();

        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isOk();

        Thread.sleep(5000);

        webTestClient.get()
                .uri("/echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    @DirtiesContext
    void shouldReturn503ServiceUnavailableWhenUpstreamIsUnreachable() {
        Map<String, Object> badRoutePayload = Map.of(
                "path", "/unreachable-echo",
                "upstreamUrl", "http://localhost:9999",
                "capacity", 10,
                "refillRate", 1,
                "volumeLimit", 100,
                "windowSize", 60
        );

        webTestClient.post()
                .uri("/management/tenants/{tenantId}/routes", this.validTenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(badRoutePayload)
                .exchange()
                .expectStatus().isCreated();

        webTestClient.get()
                .uri("/unreachable-echo")
                .header("X-API-Key", this.validApiKey)
                .exchange()
                .expectStatus().isEqualTo(503);
    }
}
