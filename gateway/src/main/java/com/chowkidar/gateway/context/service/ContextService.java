package com.chowkidar.gateway.context.service;

import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.persistence.mappers.RouteMapper;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextService {

    private final TenantRepository tenantRepository;
    private final RouteRepository routeRepository;
    private final long cacheTtlMs;

    private final ConcurrentHashMap<String, CachedContext<TenantContext>> cache = new ConcurrentHashMap<>();

    public ContextService(TenantRepository tenantRepository, RouteRepository routeRepository, @Value("${chowkidar.cache.ttl-ms:30000}") long cacheTtlMs) {
        this.tenantRepository = tenantRepository;
        this.routeRepository = routeRepository;
        this.cacheTtlMs = cacheTtlMs;
    }

    public Mono<TenantContext> resolve(String apiKey) {
        CachedContext<TenantContext> cached = cache.get(apiKey);

        if (cached != null && !cached.isExpired()) {
            return Mono.just(cached.value());
        }

        cache.remove(apiKey);

        return tenantRepository.findByApiKey(apiKey)
                .flatMap(tenantEntity -> {
                    Tenant tenant = TenantMapper.toContext(tenantEntity);
                    return routeRepository.findByTenantId(tenantEntity.id)
                            .map(RouteMapper::toContext)
                            .collectList()
                            .map(routes -> new TenantContext(tenant,  routes));
                })
                .doOnNext(tenantContext -> {
                    long expiry = System.currentTimeMillis() + cacheTtlMs;
                    cache.put(apiKey, new CachedContext<>(tenantContext, expiry));
                });
    }

    public void invalidate(String apiKey) {
        cache.remove(apiKey);
    }

    public record CachedContext<T>(T value, long expiryTime) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
