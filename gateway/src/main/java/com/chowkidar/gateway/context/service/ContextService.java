package com.chowkidar.gateway.context.service;

import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.persistence.mappers.RouteMapper;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.RouteRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContextService {

    private final TenantRepository tenantRepository;
    private final RouteRepository routeRepository;

    private final ConcurrentHashMap<String, CachedContext<TenantContext>> cache = new ConcurrentHashMap<>();

    public ContextService(TenantRepository tenantRepository, RouteRepository routeRepository) {
        this.tenantRepository = tenantRepository;
        this.routeRepository = routeRepository;
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
                    long expiry = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                    cache.put(apiKey, new CachedContext<>(tenantContext, expiry));
                });
    }

    public record CachedContext<T>(T value, long expiryTime) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
