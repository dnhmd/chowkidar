package com.chowkidar.gateway.management.controller;

import com.chowkidar.gateway.management.dto.request.*;
import com.chowkidar.gateway.management.dto.response.IpRuleResponse;
import com.chowkidar.gateway.management.service.TenantIpRuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/management/tenants/{tenantId}/ip-rules")
public class TenantIpRuleController {

    private final TenantIpRuleService tenantIpRuleService;

    public TenantIpRuleController(TenantIpRuleService tenantIpRuleService) {
        this.tenantIpRuleService = tenantIpRuleService;
    }

    @PostMapping
    public Mono<ResponseEntity<IpRuleResponse>> createIpRule(@PathVariable("tenantId") UUID tenantId, @Valid @RequestBody CreateIpRuleRequest createIpRuleRequest) {
        return tenantIpRuleService.create(tenantId, createIpRuleRequest)
                .map(ipRuleResponse -> ResponseEntity.status(HttpStatus.CREATED).body(ipRuleResponse));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<IpRuleResponse>> getIpRule(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id) {
        return tenantIpRuleService.getById(tenantId, id)
                .map(ipRuleResponse -> ResponseEntity.status(HttpStatus.OK).body(ipRuleResponse));
    }

    @GetMapping
    public Flux<IpRuleResponse> getAll(@PathVariable("tenantId") UUID tenantId) {
        return tenantIpRuleService.getAllByTenant(tenantId);
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<IpRuleResponse>> updateIpRule(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id, @Valid @RequestBody UpdateIpRuleActionRequest updateIpRuleActionRequest) {
        return tenantIpRuleService.update(tenantId, id, updateIpRuleActionRequest)
                .map(routeResponse -> ResponseEntity.status(HttpStatus.OK).body(routeResponse));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteRoute(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id) {
        return tenantIpRuleService.delete(tenantId, id)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
