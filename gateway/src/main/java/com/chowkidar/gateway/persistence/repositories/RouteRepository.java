package com.chowkidar.gateway.persistence.repositories;

import com.chowkidar.gateway.persistence.entity.RouteEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RouteRepository extends ReactiveCrudRepository<RouteEntity, UUID> {
    Flux<RouteEntity> findByTenantId(UUID tenantId);
}
