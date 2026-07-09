package com.chowkidar.gateway.context.model;

import java.util.UUID;

public record Tenant(
        UUID id,
        String name,
        String apiKeyHash
) {
}
