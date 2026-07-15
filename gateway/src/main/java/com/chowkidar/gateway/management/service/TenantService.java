package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.TenantRequest;
import com.chowkidar.gateway.management.dto.response.TenantResponseWithApiKey;
import com.chowkidar.gateway.management.dto.response.TenantResponse;
import com.chowkidar.gateway.persistence.entity.TenantApiKeysEntity;
import com.chowkidar.gateway.persistence.entity.TenantEntity;
import com.chowkidar.gateway.persistence.mappers.TenantMapper;
import com.chowkidar.gateway.persistence.repositories.TenantApiKeysRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import com.chowkidar.gateway.security.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantApiKeysRepository tenantApiKeysRepository;
    private final ContextService contextService;
    private final String hmacSecret;
    private final Integer apiKeyGraceHours;

    public TenantService(
            TenantRepository tenantRepository,
            TenantApiKeysRepository tenantApiKeysRepository,
            ContextService contextService,
            @Value("${chowkidar.security.hmac-secret:chowkidar-default-secret-change-in-production}") String hmacSecret,
            @Value(("${chowkidar.security.api-key-grace-period-hours:12}")) Integer apiKeyGraceHours
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantApiKeysRepository = tenantApiKeysRepository;
        this.contextService = contextService;
        this.hmacSecret = hmacSecret;
        this.apiKeyGraceHours = apiKeyGraceHours;
    }

    public Mono<TenantResponseWithApiKey> create(TenantRequest tenantRequest) {
        String apiKey = UUID.randomUUID().toString().replace("-", "");
        String apiKeyHash = HmacUtils.hash(apiKey, hmacSecret);
        return tenantRepository.save(new TenantEntity(tenantRequest.name(), apiKeyHash))
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponseWithApiKey(
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

    public Mono<TenantResponseWithApiKey> rotateApiKey(UUID id) {
        String newApiKey = UUID.randomUUID().toString().replace("-", "");
        String newApiKeyHash = HmacUtils.hash(newApiKey, hmacSecret);

        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .flatMap(tenantEntity -> {
                    Instant previousKeyExpiresAt = Instant.now().plus(apiKeyGraceHours, ChronoUnit.HOURS);
                    String oldApiKeyHash = tenantEntity.apiKeyHash;

                    TenantApiKeysEntity tenantApiKeysEntity = new TenantApiKeysEntity(
                            tenantEntity.id,
                            tenantEntity.apiKeyHash,
                            previousKeyExpiresAt
                    );

                    TenantEntity updatedTenant = new TenantEntity(
                            tenantEntity.id,
                            tenantEntity.name,
                            newApiKeyHash,
                            tenantEntity.status,
                            tenantEntity.createdAt
                    );

                    return tenantApiKeysRepository.save(tenantApiKeysEntity)
                            .then(tenantRepository.save(updatedTenant))
                            .doOnNext(savedtenantEntity -> contextService.invalidate(oldApiKeyHash));
                })
                .map(TenantMapper::toContext)
                .map(tenant -> new TenantResponseWithApiKey(
                        tenant.id(),
                        tenant.name(),
                        newApiKey
                ));
    }

    public Mono<Void> delete(UUID id) {
        return tenantRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id)))
                .doOnNext(tenantEntity -> contextService.invalidate(tenantEntity.apiKeyHash))
                .flatMap(tenantRepository::delete);
    }
}
