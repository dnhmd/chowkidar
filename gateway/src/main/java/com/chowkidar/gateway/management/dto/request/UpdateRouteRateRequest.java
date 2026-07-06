package com.chowkidar.gateway.management.dto.request;

public record UpdateRouteRateRequest(
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize
) {
}
