package com.chowkidar.gateway.context;

public record Route(
        String path,
        String upstreamUrl,
        Integer capacity,
        Integer refillRate,
        Integer volumeLimit,
        Integer windowSize
) {
}
