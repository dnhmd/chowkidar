package com.chowkidar.gateway.persistence.repositories;

import com.chowkidar.gateway.persistence.entity.TenantIpRuleEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantIpRuleRepository extends ReactiveCrudRepository<TenantIpRuleEntity, UUID> {
    Flux<TenantIpRuleEntity> findByTenantId(UUID tenantId);
}
