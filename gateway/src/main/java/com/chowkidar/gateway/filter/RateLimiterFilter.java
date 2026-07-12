package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.ratelimit.limiter.RateLimiter;
import com.chowkidar.gateway.ratelimit.model.RateLimitResult;
import net.logstash.logback.argument.StructuredArguments;
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

import java.time.Instant;

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
                    .onErrorResume(ex -> localRateLimiter.limit(tenant, matchedRoute));
            Mono<RateLimitResult> slidingWindowCheck = slidingWindowLimiter.limit(tenant, matchedRoute)
                    .onErrorResume(ex -> localRateLimiter.limit(tenant, matchedRoute));

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
                        long durationMs = System.currentTimeMillis() - startTime;
                        log.info("Request Completed",
                                    StructuredArguments.keyValue("tenantId", tenant.id()),
                                    StructuredArguments.keyValue("path", requestedPath),
                                StructuredArguments.keyValue("method", exchange.getRequest().getMethod().name()),
                                    StructuredArguments.keyValue("status", exchange.getResponse().getStatusCode()),
                                    StructuredArguments.keyValue("durationMs", durationMs),
                                    StructuredArguments.keyValue("signal", signal.name())
                                );
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
