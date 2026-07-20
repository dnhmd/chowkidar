package com.chowkidar.gateway.management.service;

import com.chowkidar.gateway.management.dto.request.CreateIpRuleRequest;
import com.chowkidar.gateway.management.dto.request.UpdateIpRuleActionRequest;
import com.chowkidar.gateway.management.dto.response.IpRuleResponse;
import com.chowkidar.gateway.persistence.entity.TenantIpRuleEntity;
import com.chowkidar.gateway.persistence.mappers.TenantIpRuleMapper;
import com.chowkidar.gateway.persistence.repositories.TenantIpRuleRepository;
import com.chowkidar.gateway.persistence.repositories.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Service
public class TenantIpRuleService {

    private static final Logger log = LoggerFactory.getLogger(TenantIpRuleService.class);

    private final TenantIpRuleRepository tenantIpRuleRepository;
    private final TenantRepository tenantRepository;
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    public TenantIpRuleService(TenantIpRuleRepository tenantIpRuleRepository, TenantRepository tenantRepository, ReactiveRedisTemplate<String, String> reactiveRedisTemplate) {
        this.tenantIpRuleRepository = tenantIpRuleRepository;
        this.tenantRepository = tenantRepository;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    public Mono<IpRuleResponse> create(UUID tenantId, CreateIpRuleRequest createIpRuleRequest) {
        String redisKey = "iprule:" + tenantId.toString() + ":" + createIpRuleRequest.ipAddress();

        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> tenantIpRuleRepository.save(new TenantIpRuleEntity(
                        tenantId,
                        createIpRuleRequest.ipAddress(),
                        createIpRuleRequest.action()
                )))
                .flatMap(savedEntity -> reactiveRedisTemplate.opsForValue().delete(redisKey)
                        .thenReturn(savedEntity)
                )
                .map(TenantIpRuleMapper::toContext)
                .map(tenantIpRule -> new IpRuleResponse(
                        tenantIpRule.id(),
                        tenantIpRule.ipAddress(),
                        tenantIpRule.action()
                ))
                .doOnNext(ipRuleResponse -> log.info("TenantIpRuleService | event=tenant_ip_rule_created",
                        keyValue("ruleId", ipRuleResponse.id()),
                        keyValue("tenantId", tenantId),
                        keyValue("ipAddress", ipRuleResponse.ipAddress()),
                        keyValue("action", ipRuleResponse.action())
                ));
    }

    public Mono<IpRuleResponse> getById(UUID tenantId, UUID id) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> tenantIpRuleRepository.findById(id)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant IP Rule not found: " + id)))
                        .map(TenantIpRuleMapper::toContext)
                        .map(tenantIpRule -> new IpRuleResponse(
                                tenantIpRule.id(),
                                tenantIpRule.ipAddress(),
                                tenantIpRule.action()
                        ))
                );
    }

    public Flux<IpRuleResponse> getAllByTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMapMany(tenantEntity -> tenantIpRuleRepository.findByTenantId(tenantId)
                        .map(TenantIpRuleMapper::toContext)
                        .map(tenantIpRule -> new IpRuleResponse(
                                tenantIpRule.id(),
                                tenantIpRule.ipAddress(),
                                tenantIpRule.action()
                        ))
                );
    }

    public Mono<IpRuleResponse> update(UUID tenantId, UUID id, UpdateIpRuleActionRequest updateIpRuleActionRequest) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> tenantIpRuleRepository.findById(id)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant IP Rule not found: " + id)))
                        .flatMap(tenantIpRuleEntity -> tenantIpRuleRepository.save(new TenantIpRuleEntity(
                                tenantIpRuleEntity.id,
                                tenantIpRuleEntity.tenantId,
                                tenantIpRuleEntity.ipAddress,
                                updateIpRuleActionRequest.action(),
                                tenantIpRuleEntity.createdAt
                        )))
                        .flatMap(updatedEntity -> reactiveRedisTemplate.opsForValue().delete(
                                        "iprule:" + tenantId + ":" + updatedEntity.ipAddress
                                        )
                                .thenReturn(updatedEntity)
                        )
                        .map(TenantIpRuleMapper::toContext)
                        .map(tenantIpRule -> new IpRuleResponse(
                                tenantIpRule.id(),
                                tenantIpRule.ipAddress(),
                                tenantIpRule.action()
                        ))
                );
    }

    public Mono<Void> delete(UUID tenantId, UUID id) {
        return tenantRepository.findById(tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId)))
                .flatMap(tenantEntity -> tenantIpRuleRepository.findById(id)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant IP Rule not found: " + id)))
                        .flatMap(tenantIpRuleEntity -> tenantIpRuleRepository.delete(tenantIpRuleEntity)
                                .then(reactiveRedisTemplate.opsForValue().delete("iprule:" + tenantId + ":" + tenantIpRuleEntity.ipAddress))
                                .then(Mono.fromRunnable(() -> log.info("TenantIpRuleService | event=tenant_ip_rule_deleted",
                                        keyValue("ruleId", tenantIpRuleEntity.id),
                                        keyValue("tenantId", tenantId),
                                        keyValue("ipAddress", tenantIpRuleEntity.ipAddress),
                                        keyValue("action", tenantIpRuleEntity.action)
                                ))))
                        );
    }
}
