package com.chowkidar.gateway.context.model;

import java.util.UUID;

public record Route(
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
