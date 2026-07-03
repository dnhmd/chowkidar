package com.chowkidar.gateway.ratelimit.model;

public record RateLimitResult(
        Integer limit,
        Integer remaining,
        Integer reset,
        Boolean isAllowed,
        Integer retryAfter
) {
}
