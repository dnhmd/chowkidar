package com.chowkidar.gateway.persistence.repositories;

import com.chowkidar.gateway.persistence.entity.TenantEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRepository extends ReactiveCrudRepository<TenantEntity, UUID> {
    Mono<TenantEntity> findByApiKeyHash(String apiKey);
}
