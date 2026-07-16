package com.chowkidar.gateway.health;

import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
public class HealthCheckScheduler implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final RouteRepository routeRepository;
    private final RouteHealthRegistry routeHealthRegistry;
    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final long intervalMs;
    private final long probeTimeoutMs;
    private final int concurrencyLimit;
    private final int consecutiveFailureThreshold;

    private final ConcurrentHashMap<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    public HealthCheckScheduler(
            RouteRepository routeRepository,
            RouteHealthRegistry routeHealthRegistry,
            WebClient webClient,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${chowkidar.health.interval-ms:30000}") long intervalMs,
            @Value("${chowkidar.health.timeout-ms:3000}") long probeTimeoutMs,
            @Value("${chowkidar.health.concurrency-limit:10}") int concurrencyLimit,
            @Value("${chowkidar.health.failure-threshold:3}") int consecutiveFailureThreshold
    ) {
        this.routeRepository = routeRepository;
        this.routeHealthRegistry = routeHealthRegistry;
        this.webClient = webClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.intervalMs = intervalMs;
        this.probeTimeoutMs = probeTimeoutMs;
        this.concurrencyLimit = concurrencyLimit;
        this.consecutiveFailureThreshold = consecutiveFailureThreshold;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("HealthCheckScheduler | event=scheduler_starting",
                keyValue("intervalMs", intervalMs),
                keyValue("timeoutMs", probeTimeoutMs),
                keyValue("concurrencyLimit", concurrencyLimit),
                keyValue("failureThreshold", consecutiveFailureThreshold)
        );

        routeRepository.findAll()
                .doOnNext(routeEntity -> {
                    RouteHealthEntry routeHealthEntry = new RouteHealthEntry(
                            routeEntity.id,
                            routeEntity.upstreamUrl,
                            routeEntity.fallbackUrl,
                            routeEntity.path
                    );
                    routeHealthRegistry.register(routeHealthEntry);
                })
                .thenMany(Flux.defer(() -> {
                    log.info("HealthCheckScheduler | event=initial_registry_population_complete");
                    return Flux.interval(Duration.ZERO, Duration.ofMillis(intervalMs))
                            // .doOnNext(tick -> log.info("HealthCheckScheduler | event=tick", keyValue("tick", tick)))
                            .flatMap(tick -> Flux.fromIterable(routeHealthRegistry.getAll()))
                            .flatMap(this::probeRoute, concurrencyLimit);
                }))
                .subscribe(
                        null,
                        ex -> log.error("HealthCheckScheduler | event=scheduler_fatal_error", ex),
                        () -> log.info("HealthCheckScheduler | event=scheduler_terminated")
                );
    }

    private Mono<Void> probeRoute(RouteHealthEntry routeHealthEntry) {
        String targetUrl = routeHealthEntry.upstreamUrl() + routeHealthEntry.path();
        String redisKey = "route:health:" + routeHealthEntry.routeId();

        return webClient.get()
                .uri(targetUrl)
                .exchangeToMono(response -> handleProbeResult(routeHealthEntry, redisKey, response.statusCode().is2xxSuccessful(), response.statusCode().value()))
                .timeout(Duration.ofMillis(probeTimeoutMs))
                .onErrorResume(ex -> handleProbeResult(routeHealthEntry, redisKey, false, 0));
    }

    private Mono<Void> handleProbeResult(RouteHealthEntry routeHealthEntry, String redisKey, boolean currentProbeHealthy, int statusCode) {
        String routeId = routeHealthEntry.routeId().toString();

        if (currentProbeHealthy) {
            failureCounters.remove(routeId);
            return evaluateStateAndSave(routeHealthEntry, redisKey, "UP", statusCode);
        }

        int currentFailures = failureCounters.computeIfAbsent(routeId, k -> new AtomicInteger(0)).incrementAndGet();

        if (currentFailures >= consecutiveFailureThreshold) {
            return evaluateStateAndSave(routeHealthEntry, redisKey, "DOWN", statusCode);
        }

        return Mono.empty();
    }

    private Mono<Void> evaluateStateAndSave(RouteHealthEntry routeHealthEntry, String redisKey, String evaluatedStatus, int statusCode) {
        return redisTemplate.opsForValue().get(redisKey)
                .map(cachedJson -> {
                    try {
                        JsonNode node = objectMapper.readTree(cachedJson);
                        return node.has("status") ? node.get("status").asText() : "UNKNOWN";
                    } catch (Exception e) {
                        return "UNKNOWN";
                    }
                })
                .defaultIfEmpty("UNKNOWN")
                .flatMap(previousStatus -> {
                    if (!previousStatus.equalsIgnoreCase(evaluatedStatus) && !"UNKNOWN".equals(previousStatus)) {
                        log.warn("HealthCheckScheduler | event=route_state_changed",
                                keyValue("routeId", routeHealthEntry.routeId()),
                                keyValue("oldState", previousStatus),
                                keyValue("newState", evaluatedStatus),
                                keyValue("statusCode", statusCode)
                        );
                    }

                    String payload = "{\"status\":\"%s\",\"statusCode\":%d,\"timestamp\":%d}"
                            .formatted(evaluatedStatus, statusCode, System.currentTimeMillis());

                    return redisTemplate.opsForValue()
                            .set(redisKey, payload, Duration.ofMillis(intervalMs * 3))
                            .then();
                });
    }
}
