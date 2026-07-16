package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record RouteHealthResponse(
        UUID routeId,
        String path,
        String upstreamUrl,
        String fallbackUrl,
        String status,
        Integer statusCode,
        Long timestamp
) {
}
