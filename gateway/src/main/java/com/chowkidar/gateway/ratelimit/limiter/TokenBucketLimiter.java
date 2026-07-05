package com.chowkidar.gateway.ratelimit.limiter;

import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Component
@Qualifier("tokenBucketLimiter")
public class TokenBucketLimiter implements RateLimiter {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public TokenBucketLimiter(ReactiveRedisTemplate<String, String> reactiveRedisTemplate, @Qualifier("tokenBucketScript") RedisScript<Long> rateLimitScript) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.rateLimitScript = rateLimitScript;
    }

    @Override
    public Mono<RateLimitResult> limit(Tenant tenant, Route route) {
        String redisKey = "ratelimit:velocity:" + tenant.id() + ":" + route.path();

        long nowInSeconds = Instant.now().getEpochSecond();
        long requestedTokens = 1;

        Flux<Long> results = reactiveRedisTemplate.execute(
                rateLimitScript,
                List.of(redisKey),
                String.valueOf(route.capacity()),
                String.valueOf(route.refillRate()),
                String.valueOf(nowInSeconds),
                String.valueOf(requestedTokens)
        );

        return results.next().map(result -> {
            boolean allowed = result == 1L;
            return new RateLimitResult(
                    route.capacity(),
                    allowed ? route.capacity() : 0,
                    route.refillRate(),
                    allowed,
                    allowed ? 0 : 1
            );
        });
    }
}
