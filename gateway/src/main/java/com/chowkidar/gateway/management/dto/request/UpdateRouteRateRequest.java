package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.Positive;

public record UpdateRouteRateRequest(
        @Positive Integer capacity,
        @Positive Integer refillRate,
        @Positive Integer volumeLimit,
        @Positive Integer windowSize
) {
}
