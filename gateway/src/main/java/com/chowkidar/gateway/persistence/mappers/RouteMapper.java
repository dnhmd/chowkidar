package com.chowkidar.gateway.persistence.mappers;

import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.persistence.entity.RouteEntity;

public class RouteMapper {

    public static Route toContext(RouteEntity entity) {
        return new Route(
                entity.id,
                entity.path,
                entity.upstreamUrl,
                entity.timeoutMs,
                entity.capacity,
                entity.refillRate,
                entity.volumeLimit,
                entity.windowSize,
                entity.requiresIdempotency
        );
    }
}
