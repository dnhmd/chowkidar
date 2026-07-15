package com.chowkidar.gateway.persistence.repositories;

import com.chowkidar.gateway.persistence.entity.TenantApiKeysEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantApiKeysRepository extends ReactiveCrudRepository<TenantApiKeysEntity, UUID> {
    Mono<TenantApiKeysEntity> findByPreviousApiKeyHash(String previousApiKeyHash);
}
