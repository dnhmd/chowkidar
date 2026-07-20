package com.chowkidar.gateway.context.model;

import java.util.UUID;

public record TenantIpRule(
        UUID id,
        UUID tenantId,
        String ipAddress,
        String action
) {
}
