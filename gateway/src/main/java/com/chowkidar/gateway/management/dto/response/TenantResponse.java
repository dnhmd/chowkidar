package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String apiKey
) {
}
