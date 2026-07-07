package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateRouteRequest(
        @NotBlank String path,
        @NotBlank String upstreamUrl,
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize
) {
}
