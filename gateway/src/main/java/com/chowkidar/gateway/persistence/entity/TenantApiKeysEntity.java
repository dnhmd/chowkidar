package com.chowkidar.gateway.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table(name = "tenant_api_keys")
public class TenantApiKeysEntity {

    @Id
    @Column("tenant_id")
    public final UUID tenantId;
    @Column("previous_api_key_hash")
    public final String previousApiKeyHash;
    @Column("previous_key_expires_at")
    public final Instant previousKeyExpiresAt;

    @PersistenceCreator
    public TenantApiKeysEntity(UUID tenantId, String previousApiKeyHash, Instant previousKeyExpiresAt) {
        this.tenantId = tenantId;
        this.previousApiKeyHash = previousApiKeyHash;
        this.previousKeyExpiresAt = previousKeyExpiresAt;
    }
}
