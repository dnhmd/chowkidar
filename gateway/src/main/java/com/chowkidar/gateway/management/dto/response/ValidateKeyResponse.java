package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record ValidateKeyResponse(
        UUID tenantId,
        String tenantName,
        String status,
        Boolean isDeprecated
) {
}
