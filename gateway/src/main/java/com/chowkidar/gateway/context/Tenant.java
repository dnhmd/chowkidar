package com.chowkidar.gateway.context;

import java.util.UUID;

public record Tenant(
        UUID id,
        String name,
        String apiKey
) {
}
