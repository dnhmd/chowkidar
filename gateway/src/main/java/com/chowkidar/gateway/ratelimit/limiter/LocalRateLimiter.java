package com.chowkidar.gateway.ratelimit.limiter;

import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Qualifier("localRateLimiter")
public class LocalRateLimiter implements RateLimiter {

    ConcurrentHashMap<String, AtomicReference<BucketState>> localBuckets = new ConcurrentHashMap<>();

    @Override
    public Mono<RateLimitResult> limit(Tenant tenant, Route route) {
        String key = "ratelimit:local:" + tenant.id() + ":" + route.path();

        long nowInSeconds = Instant.now().getEpochSecond();
        long requestedTokens = 1;

        AtomicReference<BucketState> ref = localBuckets.computeIfAbsent(key,
                k -> new AtomicReference<>(new BucketState(route.capacity(), nowInSeconds)));

        boolean allowed = false;
        while (true) {
            BucketState current = ref.get();

            long elapsed = Math.max(0, nowInSeconds - current.lastRefillTs());
            double newTokens = Math.min(route.capacity(), current.tokens() + (elapsed * route.refillRate()));

            BucketState newState;
            if (newTokens >= requestedTokens) {
                allowed = true;
                newState = new BucketState(newTokens - requestedTokens, nowInSeconds);
            } else {
                newState = new BucketState(newTokens, nowInSeconds);
            }

            if (ref.compareAndSet(current, newState)) {
                break;
            }
        }

        boolean finalAllowed = allowed;
        return Mono.just(new RateLimitResult(
                route.capacity(),
                finalAllowed ? (int) Math.floor(ref.get().tokens()) : 0,
                route.refillRate(),
                finalAllowed,
                finalAllowed ? 0 : 1
        ));
    }

    record BucketState(double tokens, long lastRefillTs) {}
}
