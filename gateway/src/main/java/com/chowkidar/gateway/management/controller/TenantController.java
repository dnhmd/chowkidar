package com.chowkidar.gateway.management.controller;

import com.chowkidar.gateway.management.dto.request.TenantRequest;
import com.chowkidar.gateway.management.dto.response.TenantResponseWithApiKey;
import com.chowkidar.gateway.management.dto.response.TenantResponse;
import com.chowkidar.gateway.management.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/management/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public Mono<ResponseEntity<TenantResponseWithApiKey>> createTenant(@Valid @RequestBody TenantRequest tenantRequest) {
        return tenantService.create(tenantRequest)
                .map(tenantResponse -> ResponseEntity.status(HttpStatus.CREATED).body(tenantResponse));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TenantResponse>> getTenant(@PathVariable("id") UUID id) {
        return tenantService.getById(id)
                .map(tenantResponse -> ResponseEntity.status(HttpStatus.OK).body(tenantResponse));
    }

    @GetMapping
    public Flux<TenantResponse> getAll() {
        return tenantService.getAll();
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<TenantResponse>> updateTenant(@PathVariable("id") UUID id, @Valid @RequestBody TenantRequest tenantRequest) {
        return tenantService.update(id, tenantRequest)
                .map(tenantResponse -> ResponseEntity.status(HttpStatus.OK).body(tenantResponse));
    }

    @PostMapping("/{id}/rotate-key")
    public Mono<ResponseEntity<TenantResponseWithApiKey>> rotateKey(@PathVariable("id") UUID id) {
        return tenantService.rotateApiKey(id)
                .map(tenantResponse -> ResponseEntity.status(HttpStatus.OK).body(tenantResponse));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteTenant(@PathVariable("id") UUID id) {
        return tenantService.delete(id)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
