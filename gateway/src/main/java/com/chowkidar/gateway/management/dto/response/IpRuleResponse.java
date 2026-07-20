package com.chowkidar.gateway.management.dto.response;

import java.util.UUID;

public record IpRuleResponse(
        UUID id,
        String ipAddress,
        String action
) {
}
