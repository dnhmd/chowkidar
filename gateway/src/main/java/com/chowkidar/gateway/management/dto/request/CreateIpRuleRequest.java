package com.chowkidar.gateway.management.dto.request;

public record CreateIpRuleRequest(
        String ipAddress,
        String action
) {
}
