package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateRouteUrlRequest(
        @NotBlank String upstreamUrl,
        String fallbackUrl
) {
}
