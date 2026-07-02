package com.chowkidar.gateway.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table(name = "tenants")
public class TenantEntity {

    @Id
    public final UUID id;
    public final String name;
    @Column("api_key")
    public final String apiKey;
    @Column("created_at")
    public final Instant createdAt;

    public TenantEntity(String name, String apiKey) {
        this.id = null;
        this.name = name;
        this.apiKey = apiKey;
        this.createdAt = null;
    }

    @PersistenceCreator
    public TenantEntity(UUID id, String name, String apiKey, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.createdAt = createdAt;
    }
}
