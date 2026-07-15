package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record TenantResponseWithApiKey(
        UUID id,
        String name,
        String apiKey
) {
}
