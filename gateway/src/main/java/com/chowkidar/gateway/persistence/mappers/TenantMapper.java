package com.chowkidar.gateway.persistence.mappers;

import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.persistence.entity.TenantEntity;

public class TenantMapper {

    public static Tenant toContext(TenantEntity entity) {
        return new Tenant(
                entity.id,
                entity.name,
                entity.apiKeyHash,
                entity.status
        );
    }
}
