package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.ratelimit.limiter.RateLimiter;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(2)
public class RateLimiterFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);

    private final RateLimiter tokenBucketLimiter;
    private final RateLimiter slidingWindowLimiter;
    private final RateLimiter localRateLimiter;

    public RateLimiterFilter(
            @Qualifier("tokenBucketLimiter") RateLimiter tokenBucketLimiter,
            @Qualifier("slidingWindowLimiter") RateLimiter slidingWindowLimiter,
            @Qualifier("localRateLimiter") RateLimiter localRateLimiter) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
        this.localRateLimiter = localRateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.shouldBypassFilters(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);

        long startTime = System.currentTimeMillis();

        return Mono.deferContextual(contextView -> {
            TenantContext tenantContext = contextView.getOrEmpty(TenantContext.class)
                    .map(tenantContextObject -> (TenantContext) tenantContextObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context missing"));

            String requestedPath = exchange.getRequest().getURI().getPath();

            Route matchedRoute = tenantContext.routes().stream()
                    .filter(route -> requestedPath.startsWith(route.path()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found"));

            Tenant tenant = tenantContext.tenant();

            Mono<RateLimitResult> tokenBucketCheck = tokenBucketLimiter.limit(tenant, matchedRoute)
                    .onErrorResume(ex -> {
                        logFallbackToLocalLimiter(tenant, matchedRoute, "TOKEN_BUCKET", ex);
                        return localRateLimiter.limit(tenant, matchedRoute);
                    });
            Mono<RateLimitResult> slidingWindowCheck = slidingWindowLimiter.limit(tenant, matchedRoute)
                    .onErrorResume(ex -> {
                        logFallbackToLocalLimiter(tenant, matchedRoute, "SLIDING_WINDOW", ex);
                        return localRateLimiter.limit(tenant, matchedRoute);
                    });

            return Mono.zip(tokenBucketCheck, slidingWindowCheck)
                    .flatMap(tuple -> {
                        RateLimitResult tokenBucketResult = tuple.getT1();
                        RateLimitResult slidingWindowResult = tuple.getT2();

                        if (!tokenBucketResult.isAllowed() || !slidingWindowResult.isAllowed()) {
                            boolean isTokenBucketFailure = !tokenBucketResult.isAllowed();
                            RateLimitResult primaryFailure = !tokenBucketResult.isAllowed() ? tokenBucketResult : slidingWindowResult;
                            String failedLimiterType = isTokenBucketFailure ? "TOKEN_BUCKET" : "SLIDING_WINDOW";

                            log.warn("RateLimiterFilter | event=rate_limit_denied",
                                    keyValue("tenantId", tenant.id()),
                                    keyValue("path", matchedRoute.path()),
                                    keyValue("limiterType", failedLimiterType)
                            );

                            populateHeaders(exchange.getResponse(), primaryFailure);
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return exchange.getResponse().setComplete();
                        }

                        RateLimitResult restrictiveResult = tokenBucketResult.remaining() < slidingWindowResult.remaining() ? tokenBucketResult : slidingWindowResult;

                        populateHeaders(exchange.getResponse(), restrictiveResult);
                        return chain.filter(exchange).contextWrite(context -> context.put(Route.class, matchedRoute));
                    })
                    .doFinally(signal -> {
                        long durationMs = System.currentTimeMillis() - startTime;
                        log.info("RateLimiterFilter | event=request_completed",
                                keyValue("tenantId", tenant.id()),
                                keyValue("path", requestedPath),
                                keyValue("method", exchange.getRequest().getMethod().name()),
                                keyValue("status", exchange.getResponse().getStatusCode()),
                                keyValue("durationMs", durationMs),
                                keyValue("signal", signal.name())
                        );
                    });
        });
    }

    private static void logFallbackToLocalLimiter(Tenant tenant, Route matchedRoute, String limiterType, Throwable ex) {
        log.warn("RateLimiterFilter | event=fallback_to_local",
                keyValue("tenantId", tenant.id()),
                keyValue("path", matchedRoute.path()),
                keyValue("limiterType", limiterType),
                keyValue("reason", ex.getMessage())
        );
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
