package com.chowkidar.gateway.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table(name = "routes")
public class RouteEntity {

    @Id
    public final UUID id;
    @Column("tenant_id")
    public final UUID tenantId;
    public final String path;
    @Column("upstream_url")
    public final String upstreamUrl;
    @Column("fallback_url")
    public final String fallbackUrl;
    @Column("timeout_ms")
    public final Integer timeoutMs;
    public final Integer capacity;
    @Column("refill_rate")
    public final Integer refillRate;
    @Column("volume_limit")
    public final Integer volumeLimit;
    @Column("window_size")
    public final Integer windowSize;
    @Column("requires_idempotency")
    public final Boolean requiresIdempotency;
    @Column("created_at")
    public final Instant createdAt;

    public RouteEntity(UUID tenantId, String path, String upstreamUrl, String fallbackUrl, Integer timeoutMs, Integer capacity, Integer refillRate, Integer volumeLimit, Integer windowSize, Boolean requiresIdempotency) {
        this.id = null;
        this.tenantId = tenantId;
        this.path = path;
        this.upstreamUrl = upstreamUrl;
        this.fallbackUrl = fallbackUrl;
        this.timeoutMs = timeoutMs;
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.volumeLimit = volumeLimit;
        this.windowSize = windowSize;
        this.requiresIdempotency = false;
        this.createdAt = null;
    }

    @PersistenceCreator
    public RouteEntity(UUID id, UUID tenantId, String path, String upstreamUrl, String fallbackUrl, Integer timeoutMs, Integer capacity, Integer refillRate, Integer volumeLimit, Integer windowSize, Boolean requiresIdempotency, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.path = path;
        this.upstreamUrl = upstreamUrl;
        this.fallbackUrl = fallbackUrl;
        this.timeoutMs = timeoutMs;
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.volumeLimit = volumeLimit;
        this.windowSize = windowSize;
        this.requiresIdempotency = requiresIdempotency;
        this.createdAt = createdAt;
    }
}
