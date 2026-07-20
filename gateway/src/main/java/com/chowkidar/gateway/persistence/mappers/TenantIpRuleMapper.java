package com.chowkidar.gateway.persistence.mappers;

import com.chowkidar.gateway.context.model.TenantIpRule;
import com.chowkidar.gateway.persistence.entity.TenantIpRuleEntity;

public class TenantIpRuleMapper {

    public static TenantIpRule toContext(TenantIpRuleEntity entity) {
        return new TenantIpRule(
                entity.id,
                entity.tenantId,
                entity.ipAddress,
                entity.action
        );
    }
}
