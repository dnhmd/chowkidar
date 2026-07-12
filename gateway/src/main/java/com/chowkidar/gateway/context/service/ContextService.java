package com.chowkidar.gateway.context.service;

import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.persistence.mappers.RouteMapper;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import com.chowkidar.gateway.security.HmacUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextService {

    private final TenantRepository tenantRepository;
    private final RouteRepository routeRepository;
    private final CircuitBreaker postgresCircuitBreaker;

    private final long cacheTtlMs;
    private final String hmacSecret;

    private final ConcurrentHashMap<String, CachedContext<TenantContext>> cache = new ConcurrentHashMap<>();

    public ContextService(
            TenantRepository tenantRepository,
            RouteRepository routeRepository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${chowkidar.cache.ttl-ms:30000}") long cacheTtlMs,
            @Value("${chowkidar.security.hmac-secret:chowkidar-default-secret-change-in-production}") String hmacSecret
    ) {
        this.tenantRepository = tenantRepository;
        this.routeRepository = routeRepository;
        this.postgresCircuitBreaker = circuitBreakerRegistry.circuitBreaker("postgres");
        this.cacheTtlMs = cacheTtlMs;
        this.hmacSecret = hmacSecret;
    }

    public Mono<TenantContext> resolve(String apiKey) {
        String apiKeyHash = HmacUtils.hash(apiKey, hmacSecret);
        CachedContext<TenantContext> cached = cache.get(apiKeyHash);

        if (cached != null && !cached.isExpired()) {
            return Mono.just(cached.value());
        }

        cache.remove(apiKeyHash);

        return tenantRepository.findByApiKeyHash(apiKeyHash)
                .flatMap(tenantEntity -> {
                    Tenant tenant = TenantMapper.toContext(tenantEntity);
                    return routeRepository.findByTenantId(tenantEntity.id)
                            .map(RouteMapper::toContext)
                            .collectList()
                            .map(routes -> new TenantContext(tenant,  routes));
                })
                .transformDeferred(CircuitBreakerOperator.of(postgresCircuitBreaker))
                .onErrorMap(CallNotPermittedException.class, ex ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable"))
                .onErrorMap(ex -> !(ex instanceof ResponseStatusException), ex ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable"))
                .doOnNext(tenantContext -> {
                    long expiry = System.currentTimeMillis() + cacheTtlMs;
                    cache.put(apiKeyHash, new CachedContext<>(tenantContext, expiry));
                });
    }

    public void invalidate(String apiKeyHash) {
        cache.remove(apiKeyHash);
    }

    public record CachedContext<T>(T value, long expiryTime) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
