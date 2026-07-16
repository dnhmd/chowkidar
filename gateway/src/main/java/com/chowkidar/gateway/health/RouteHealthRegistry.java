package com.chowkidar.gateway.health;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RouteHealthRegistry {

    private final ConcurrentHashMap<UUID, RouteHealthEntry> healthRegistry = new ConcurrentHashMap<>();

    public void register(RouteHealthEntry routeHealthEntry) {
        healthRegistry.put(routeHealthEntry.routeId(), routeHealthEntry);
    }

    public void deregister(UUID routeId) {
        healthRegistry.remove(routeId);
    }

    public void update(UUID id, RouteHealthEntry routeHealthEntry) {
        healthRegistry.replace(id, routeHealthEntry);
    }

    public Collection<RouteHealthEntry> getAll() {
        return healthRegistry.values();
    }
}
