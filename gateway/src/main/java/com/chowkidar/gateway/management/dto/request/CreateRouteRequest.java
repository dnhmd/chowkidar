package com.chowkidar.gateway.management.dto.request;

public record CreateRouteRequest(
        String path,
        String upstreamUrl,
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize
) {
}
