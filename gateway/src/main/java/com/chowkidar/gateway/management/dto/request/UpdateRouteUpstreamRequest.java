package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateRouteUpstreamRequest(
        @NotBlank String upstreamUrl
) {
}
