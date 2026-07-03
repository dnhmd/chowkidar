package com.chowkidar.gateway.ratelimit.limiter;

import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import reactor.core.publisher.Mono;

public interface RateLimiter {
    Mono<RateLimitResult> limit(Tenant tenant, Route route);
}
