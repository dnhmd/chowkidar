package com.chowkidar.gateway.context.model;

import java.util.List;

public record TenantContext(
        Tenant tenant,
        List<Route> routes,
        Boolean isDeprecated
) {
}
