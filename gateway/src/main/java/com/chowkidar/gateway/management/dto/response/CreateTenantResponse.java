package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record CreateTenantResponse(
        UUID id,
        String name,
        String apiKey
) {
}
