package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record RouteResponse(
        UUID id,
        String path,
        String upstreamUrl,
        Integer timeoutMs,
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize,
        Boolean requiresIdempotency
) {
}
