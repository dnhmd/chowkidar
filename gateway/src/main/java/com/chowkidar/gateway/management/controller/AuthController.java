package com.chowkidar.gateway.management.controller;

import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.context.service.ContextService;
import com.chowkidar.gateway.management.dto.request.ValidateKeyRequest;
import com.chowkidar.gateway.management.dto.response.ValidateKeyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/management/auth")
public class AuthController {

    private final ContextService contextService;

    public AuthController(ContextService contextService) {
        this.contextService = contextService;
    }

    @PostMapping("/validate")
    public Mono<ResponseEntity<ValidateKeyResponse>> validate(@Valid @RequestBody ValidateKeyRequest validateKeyRequest) {
        return contextService.resolve(validateKeyRequest.apiKey())
                .map(tenantContext -> new ValidateKeyResponse(
                        tenantContext.tenant().id(),
                        tenantContext.tenant().name(),
                        tenantContext.tenant().status(),
                        tenantContext.isDeprecated()
                ))
                .map(validateKeyResponse -> ResponseEntity.status(HttpStatus.OK).body(validateKeyResponse));
    }
}
