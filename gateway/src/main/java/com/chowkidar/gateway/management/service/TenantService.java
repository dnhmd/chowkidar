package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.TenantRequest;
import com.chowkidar.gateway.management.dto.response.CreateTenantResponse;
import com.chowkidar.gateway.management.dto.response.TenantResponse;
import com.chowkidar.gateway.persistence.entity.TenantEntity;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import com.chowkidar.gateway.security.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ContextService contextService;
    private final String hmacSecret;

    public TenantService(
            TenantRepository tenantRepository,
            ContextService contextService,
            @Value("${chowkidar.security.hmac-secret:chowkidar-default-secret-change-in-production}") String hmacSecret
    ) {
        this.tenantRepository = tenantRepository;
        this.contextService = contextService;
        this.hmacSecret = hmacSecret;
    }

    public Mono<CreateTenantResponse> create(TenantRequest tenantRequest) {
        String apiKey = UUID.randomUUID().toString().replace("-", "");
        String apiKeyHash = HmacUtils.hash(apiKey, hmacSecret);
        return tenantRepository.save(new TenantEntity(tenantRequest.name(), apiKeyHash))
                .map(TenantMapper::toContext)
                .map(tenant -> new CreateTenantResponse(
                        tenant.id(),
                        tenant.name(),
                        apiKey
                ));
    }

    public Mono<TenantResponse> getById(UUID id) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name()
                ));
    }

    public Flux<TenantResponse> getAll() {
        return tenantRepository.findAll()
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name()
                ));
    }

    public Mono<TenantResponse> update(UUID id, TenantRequest tenantRequest) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .flatMap(tenantEntity -> tenantRepository.save(
                        new TenantEntity(
                                tenantEntity.id,
                                tenantRequest.name(),
                                tenantEntity.apiKeyHash,
                                tenantEntity.status,
                                tenantEntity.createdAt
                        )
                ))
                .doOnNext(tenantEntity -> contextService.invalidate(tenantEntity.apiKeyHash))
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name()
                ));
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .doOnNext(tenantEntity -> contextService.invalidate(tenantEntity.apiKeyHash))
                .flatMap(tenantRepository::delete);
    }
}
