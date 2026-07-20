package com.chowkidar.gateway.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table(name = "tenant_ip_rules")
public class TenantIpRuleEntity {

    @Id
    public final UUID id;
    @Column("tenant_id")
    public final UUID tenantId;
    @Column("ip_address")
    public final String ipAddress;
    public final String action;
    @Column("created_at")
    public final Instant createdAt;

    public TenantIpRuleEntity(UUID tenantId, String ipAddress, String action) {
        this.id = null;
        this.tenantId = tenantId;
        this.ipAddress = ipAddress;
        this.action = action;
        this.createdAt = null;
    }

    @PersistenceCreator
    public TenantIpRuleEntity(UUID id, UUID tenantId, String ipAddress, String action, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.ipAddress = ipAddress;
        this.action = action;
        this.createdAt = createdAt;
    }
}
