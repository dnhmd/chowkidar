package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateRouteIdempotencyRequest(
        @NotNull Boolean requiresIdempotency
) {
}
