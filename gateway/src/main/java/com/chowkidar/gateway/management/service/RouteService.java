package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.CreateRouteRequest;
import com.chowkidar.gateway.management.dto.request.UpdateRouteRateRequest;
import com.chowkidar.gateway.management.dto.request.UpdateRouteUpstreamRequest;
import com.chowkidar.gateway.management.dto.response.RouteResponse;
import com.chowkidar.gateway.persistence.entity.RouteEntity;
import com.chowkidar.gateway.persistence.mappers.RouteMapper;
import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class RouteService {

    private final TenantRepository tenantRepository;
    private final RouteRepository routeRepository;
    private final ContextService contextService;

    private final Integer defaultCapacity;
    private final Integer defaultRefillRate;
    private final Integer defaultVolumeLimit;
    private final Integer defaultWindowSize;

    public RouteService(
            TenantRepository tenantRepository,
            RouteRepository routeRepository,
            ContextService contextService,
            @Value("${chowkidar.rate-limit.default-capacity:100}") Integer defaultCapacity,
            @Value("${chowkidar.rate-limit.default-refill-rate:10}") Integer defaultRefillRate,
            @Value("${chowkidar.rate-limit.default-volume-limit:10000}") Integer defaultVolumeLimit,
            @Value("${chowkidar.rate-limit.default-window-size:3600}") Integer defaultWindowSize
    ) {
        this.tenantRepository = tenantRepository;
        this.routeRepository = routeRepository;
        this.contextService = contextService;
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.defaultVolumeLimit = defaultVolumeLimit;
        this.defaultWindowSize = defaultWindowSize;
    }

    public Mono<RouteResponse> create(UUID tenantId, CreateRouteRequest createRouteRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.save(new RouteEntity(
                            tenantId,
                            createRouteRequest.path(),
                            createRouteRequest.upstreamUrl(),
                            createRouteRequest.capacity() != null ? createRouteRequest.capacity() : defaultCapacity,
                            createRouteRequest.refillRate() != null ? createRouteRequest.refillRate() : defaultRefillRate,
                            createRouteRequest.volumeLimit() != null ? createRouteRequest.volumeLimit() : defaultVolumeLimit,
                            createRouteRequest.windowSize() != null ? createRouteRequest.windowSize() : defaultWindowSize
                    ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    route.id(),
                                    route.path(),
                                    route.upstreamUrl(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKey));
                });
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
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize()
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
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize()
                            ));
                });
    }

    public Mono<RouteResponse> updateUpstream(UUID tenantId, UUID routeId, UpdateRouteUpstreamRequest updateRouteUpstreamRequest) {
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
                                            updateRouteUpstreamRequest.upstreamUrl(),
                                            routeEntity.capacity,
                                            routeEntity.refillRate,
                                            routeEntity.volumeLimit,
                                            routeEntity.windowSize,
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    updateRouteUpstreamRequest.upstreamUrl(),
                                    route.capacity(),
                                    route.refillRate(),
                                    route.volumeLimit(),
                                    route.windowSize()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKey));
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
                                            updateRouteRateRequest.capacity(),
                                            updateRouteRateRequest.refillRate(),
                                            updateRouteRateRequest.volumeLimit(),
                                            updateRouteRateRequest.windowSize(),
                                            routeEntity.createdAt
                                    )
                            ))
                            .map(RouteMapper::toContext)
                            .map(route -> new RouteResponse(
                                    routeId,
                                    route.path(),
                                    route.upstreamUrl(),
                                    updateRouteRateRequest.capacity(),
                                    updateRouteRateRequest.refillRate(),
                                    updateRouteRateRequest.volumeLimit(),
                                    updateRouteRateRequest.windowSize()
                            ))
                            .doOnNext(routeResponse -> contextService.invalidate(tenantEntity.apiKey));
                });
    }

    public Mono<Void> delete(UUID tenantId, UUID routeId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> {
                    return routeRepository.findById(routeId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found for tenant: " + tenantEntity.id)))
                            .doOnNext(routeEntity -> contextService.invalidate(tenantEntity.apiKey))
                            .flatMap(routeRepository::delete);
                });
    }
}
