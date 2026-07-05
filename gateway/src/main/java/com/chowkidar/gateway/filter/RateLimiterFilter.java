package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.ratelimit.limiter.RateLimiter;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(2)
public class RateLimiterFilter implements WebFilter {

    private final RateLimiter tokenBucketLimiter;
    private final RateLimiter slidingWindowLimiter;

    public RateLimiterFilter(
            @Qualifier("tokenBucketLimiter") RateLimiter tokenBucketLimiter,
            @Qualifier("slidingWindowLimiter") RateLimiter slidingWindowLimiter) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(contextView -> {
            TenantContext tenantContext = (TenantContext) contextView.getOrEmpty(TenantContext.class)
                    .map(tenantContextObject -> (TenantContext) tenantContextObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context missing"));

            String requestedPath = exchange.getRequest().getURI().getPath();

            Route matchedRoute = tenantContext.routes().stream()
                    .filter(route -> requestedPath.startsWith(route.path()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found"));

            Tenant tenant = tenantContext.tenant();

            Mono<RateLimitResult> tokenBucketCheck = tokenBucketLimiter.limit(tenant, matchedRoute);
            Mono<RateLimitResult> slidingWindowCheck = slidingWindowLimiter.limit(tenant, matchedRoute);

            return Mono.zip(tokenBucketCheck, slidingWindowCheck)
                    .flatMap(tuple -> {
                        RateLimitResult tokenBucketResult = tuple.getT1();
                        RateLimitResult slidingWindowResult = tuple.getT2();

                        if (!tokenBucketResult.isAllowed() || !slidingWindowResult.isAllowed()) {
                            RateLimitResult primaryFailure = !tokenBucketResult.isAllowed() ? tokenBucketResult : slidingWindowResult;

                            populateHeaders(exchange.getResponse(), primaryFailure);
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return exchange.getResponse().setComplete();
                        }

                        RateLimitResult restrictiveResult = tokenBucketResult.remaining() < slidingWindowResult.remaining() ? tokenBucketResult : slidingWindowResult;

                        populateHeaders(exchange.getResponse(), restrictiveResult);
                        return chain.filter(exchange).contextWrite(context -> context.put(Route.class, matchedRoute));
                    })
                    .doFinally(signal -> {
                        // TODO: emit telemetry signal
                    });
        });
    }

    private void populateHeaders(ServerHttpResponse response, RateLimitResult rateLimitResult) {
        var headers = response.getHeaders();
        headers.set("RateLimit-Limit", String.valueOf(rateLimitResult.limit()));
        headers.set("RateLimit-Remaining", String.valueOf(rateLimitResult.remaining()));
        headers.set("RateLimit-Reset", String.valueOf(rateLimitResult.reset()));

        if (!rateLimitResult.isAllowed() && rateLimitResult.retryAfter() != null) {
            headers.set("Retry-After", String.valueOf(rateLimitResult.retryAfter()));
        }
    }
}
