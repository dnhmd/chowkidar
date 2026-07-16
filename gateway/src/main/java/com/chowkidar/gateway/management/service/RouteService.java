package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.*;
import com.chowkidar.gateway.management.dto.response.RouteResponse;
import com.chowkidar.gateway.persistence.entity.RouteEntity;
import com.chowkidar.gateway.persistence.mappers.RouteMapper;
import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    private final TenantRepository tenantRepository;
    private final RouteRepository routeRepository;
    private final ContextService contextService;

    private final Integer defaultTimeoutMs;
    private final Integer defaultCapacity;
    private final Integer defaultRefillRate;
    private final Integer defaultVolumeLimit;
    private final Integer defaultWindowSize;

    public RouteService(
            TenantRepository tenantRepository,
            RouteRepository routeRepository,
            ContextService contextService,
            @Value("${chowkidar.route.default-timeout-ms:3000}") Integer defaultTimeoutMs,
            @Value("${chowkidar.rate-limit.default-capacity:100}") Integer defaultCapacity,
            @Value("${chowkidar.rate-limit.default-refill-rate:10}") Integer defaultRefillRate,
            @Value("${chowkidar.rate-limit.default-volume-limit:10000}") Integer defaultVolumeLimit,
            @Value("${chowkidar.rate-limit.default-window-size:3600}") Integer defaultWindowSize
    ) {
        this.tenantRepository = tenantRepository;
        this.routeRepository = routeRepository;
        this.contextService = contextService;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.defaultVolumeLimit = defaultVolumeLimit;
        this.defaultWindowSize = defaultWindowSize;
    }

    public Mono<RouteResponse> create(UUID tenantId, CreateRouteRequest createRouteRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> routeRepository.save(new RouteEntity(
                                tenantId,
                                createRouteRequest.path(),
                                createRouteRequest.upstreamUrl(),
                                createRouteRequest.fallbackUrl(),
                                createRouteRequest.timeoutMs() != null ? createRouteRequest.timeoutMs() : defaultTimeoutMs,
                                createRouteRequest.capacity() != null ? createRouteRequest.capacity() : defaultCapacity,
                                createRouteRequest.refillRate() != null ? createRouteRequest.refillRate() : defaultRefillRate,
                                createRouteRequest.volumeLimit() != null ? createRouteRequest.volumeLimit() : defaultVolumeLimit,
                                createRouteRequest.windowSize() != null ? createRouteRequest.windowSize() : defaultWindowSize,
                                createRouteRequest.requiresIdempotency() != null ? createRouteRequest.requiresIdempotency() : false
                        ))
                        .map(RouteMapper::toContext)
                        .map(route -> new RouteResponse(
                                route.id(),
                                route.path(),
                                route.upstreamUrl(),
                                route.fallbackUrl(),
                                route.timeoutMs(),
                                route.capacity(),
                                route.refillRate(),
                                route.volumeLimit(),
                                route.windowSize(),
                                route.requiresIdempotency()
                        ))
                        .doOnNext(routeResponse -> {
                            contextService.invalidate(tenantEntity.apiKeyHash);
                            log.info("RouteService | event=route_created",
                                    keyValue("tenantId", tenantId),
                                    keyValue("routeId", routeResponse.id()),
                                    keyValue("path", routeResponse.path())
                            );
                        }));
    }

    public Mono<RouteResponse> getById(UUID tenantId, UUID routeId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.fallbackUrl(),
                                    route.timeoutMs(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize(),
                                    route.requiresIdempotency()
                            ));
                });
    }

    public Flux<RouteResponse> getAllByTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMapMany(tenantEntity -> {
                    return routeRepository.findByTenantId(tenantId)
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    route.id(),
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.fallbackUrl(),
                                    route.timeoutMs(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize(),
                                    route.requiresIdempotency()
                            ));
                });
    }

    public Mono<RouteResponse> updateUrl(UUID tenantId, UUID routeId, UpdateRouteUrlRequest updateRouteUrlRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .flatMap(routeEntity -> routeRepository.save(
                                    new RouteEntity(
                                            routeEntity.id,
                                            routeEntity.tenantId,
                                            routeEntity.path,
                                            updateRouteUrlRequest.upstreamUrl(),
                                            updateRouteUrlRequest.fallbackUrl(),
                                            routeEntity.timeoutMs,
                                            routeEntity.capacity,
                                            routeEntity.refillRate,
                                            routeEntity.volumeLimit,
                                            routeEntity.windowSize,
                                            routeEntity.requiresIdempotency,
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    updateRouteUrlRequest.upstreamUrl(),
                                    updateRouteUrlRequest.fallbackUrl(),
                                    route.timeoutMs(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize(),
                                    route.requiresIdempotency()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKeyHash));
                });
    }

    public Mono<RouteResponse> updateRate(UUID tenantId, UUID routeId, UpdateRouteRateRequest updateRouteRateRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .flatMap(routeEntity -> routeRepository.save(
                                    new RouteEntity(
                                            routeEntity.id,
                                            routeEntity.tenantId,
                                            routeEntity.path,
                                            routeEntity.upstreamUrl,
                                            routeEntity.fallbackUrl,
                                            routeEntity.timeoutMs,
                                            updateRouteRateRequest.capacity(),
                                            updateRouteRateRequest.refillRate(),
                                            updateRouteRateRequest.volumeLimit(),
                                            updateRouteRateRequest.windowSize(),
                                            routeEntity.requiresIdempotency,
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.fallbackUrl(),
                                    route.timeoutMs(),
                                    updateRouteRateRequest.capacity(),
                                    updateRouteRateRequest.refillRate(),
                                    updateRouteRateRequest.volumeLimit(),
                                    updateRouteRateRequest.windowSize(),
                                    route.requiresIdempotency()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKeyHash));
                });
    }

    public Mono<RouteResponse> updateIdempotencyRequirement(UUID tenantId, UUID routeId, UpdateRouteIdempotencyRequest updateRouteIdempotencyRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .flatMap(routeEntity -> routeRepository.save(
                                    new RouteEntity(
                                            routeEntity.id,
                                            routeEntity.tenantId,
                                            routeEntity.path,
                                            routeEntity.upstreamUrl,
                                            routeEntity.fallbackUrl,
                                            routeEntity.timeoutMs,
                                            routeEntity.capacity,
                                            routeEntity.refillRate,
                                            routeEntity.volumeLimit,
                                            routeEntity.windowSize,
                                            updateRouteIdempotencyRequest.requiresIdempotency(),
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.fallbackUrl(),
                                    route.timeoutMs(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize(),
                                    updateRouteIdempotencyRequest.requiresIdempotency()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKeyHash));
                });
    }

    public Mono<RouteResponse> updateRouteTimeout(UUID tenantId, UUID routeId, UpdateRouteTimeoutRequest updateRouteTimeoutRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .flatMap(routeEntity -> routeRepository.save(
                                    new RouteEntity(
                                            routeEntity.id,
                                            routeEntity.tenantId,
                                            routeEntity.path,
                                            routeEntity.upstreamUrl,
                                            routeEntity.fallbackUrl,
                                            updateRouteTimeoutRequest.timeoutMs(),
                                            routeEntity.capacity,
                                            routeEntity.refillRate,
                                            routeEntity.volumeLimit,
                                            routeEntity.windowSize,
                                            routeEntity.requiresIdempotency,
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.fallbackUrl(),
                                    updateRouteTimeoutRequest.timeoutMs(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize(),
                                    route.requiresIdempotency()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKeyHash));
                });
    }

    public Mono<Void> delete(UUID tenantId, UUID routeId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> routeRepository.findById(routeId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantId)))
                        .flatMap(routeEntity -> routeRepository.delete(routeEntity)
                                .then(Mono.fromRunnable(() -> {
                                    contextService.invalidate(tenantEntity.apiKeyHash);
                                    log.info("RouteService | event=route_deleted",
                                            keyValue("tenantId", tenantId),
                                            keyValue("routeId", routeId)
                                    );
                                }))
                        ));
    }
}
