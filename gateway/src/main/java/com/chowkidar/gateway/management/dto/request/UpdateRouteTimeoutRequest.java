package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateRouteTimeoutRequest(
        @NotNull Integer timeoutMs
) {
}
