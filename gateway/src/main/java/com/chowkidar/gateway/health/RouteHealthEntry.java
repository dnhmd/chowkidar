package com.chowkidar.gateway.health;

import java.util.UUID;

public record RouteHealthEntry(
        UUID routeId,
        String upstreamUrl,
        String fallbackUrl,
        String path
) {
}
