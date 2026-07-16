package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateRouteRequest(
        @NotBlank String path,
        @NotBlank String upstreamUrl,
        String fallbackUrl,
        Integer timeoutMs,
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize,
        Boolean requiresIdempotency
) {
}
