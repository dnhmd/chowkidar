package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.TenantRequest;
import com.chowkidar.gateway.management.dto.response.TenantResponse;
import com.chowkidar.gateway.persistence.entity.TenantEntity;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
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

    public TenantService(TenantRepository tenantRepository, ContextService contextService) {
        this.tenantRepository = tenantRepository;
        this.contextService = contextService;
    }

    public Mono<TenantResponse> create(TenantRequest tenantRequest) {
        String apiKey = UUID.randomUUID().toString().replace("-", "");

        return tenantRepository.save(new TenantEntity(tenantRequest.name(), apiKey))
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name(),
                        tenant.apiKey()
                ));
    }

    public Mono<TenantResponse> getById(UUID id) {
        return tenantRepository.findById(id)
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name(),
                        tenant.apiKey()
                ));
    }

    public Flux<TenantResponse> getAll() {
        return tenantRepository.findAll()
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name(),
                        tenant.apiKey()
                ));
    }

    public Mono<TenantResponse> update(UUID id, TenantRequest tenantRequest) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")))
                .flatMap(existing -> tenantRepository.save(
                        new TenantEntity(
                                existing.id,
                                tenantRequest.name(),
                                existing.apiKey,
                                existing.createdAt
                        )
                ))
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponse(
                        tenant.id(),
                        tenant.name(),
                        tenant.apiKey()
                ))
                .doOnNext(tenant -> contextService.invalidate(tenant.apiKey()));
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")))
                .doOnNext(tenant -> contextService.invalidate(tenant.apiKey))
                .flatMap(tenantRepository::delete);
    }
}
