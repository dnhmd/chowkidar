package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(5)
public class ProxyFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ProxyFilter(WebClient webClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = webClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.shouldBypassFilters(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);

        return Mono.deferContextual(contextView -> {
            TenantContext tenantContext = contextView.getOrEmpty(TenantContext.class)
                    .map(tenantContextObject -> (TenantContext) tenantContextObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context missing"));

            Route matchedRoute = contextView.getOrEmpty(Route.class)
                    .map(matchedRouteObject -> (Route) matchedRouteObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Matched route missing"));

            UUID tenantId = tenantContext.tenant().id();
            URI originalUri = exchange.getRequest().getURI();
            String httpMethod = exchange.getRequest().getMethod().name();
            Duration routeTimeout = Duration.ofMillis(matchedRoute.timeoutMs());

            Flux<DataBuffer> cachedBody = exchange.getRequest().getBody().cache();

            URI upstreamUri = UriComponentsBuilder
                    .fromUriString(matchedRoute.upstreamUrl())
                    .replacePath(originalUri.getPath())
                    .replaceQuery(originalUri.getQuery())
                    .build(true)
                    .toUri();
            String upstream = upstreamUri.toString();

            CircuitBreaker upstreamCircuitBreaker = circuitBreakerRegistry.circuitBreaker("upstream-" + matchedRoute.id());

            return executeProxy(exchange, upstreamUri, cachedBody, routeTimeout, upstreamCircuitBreaker)
                    .doOnSuccess(v -> log.info("ProxyFilter | event=proxy_success",
                            keyValue("tenantId", tenantId),
                            keyValue("upstream", upstream),
                            keyValue("method", httpMethod),
                            keyValue("statusCode", exchange.getResponse().getStatusCode() != null ? exchange.getResponse().getStatusCode().value() : 200)
                    ))
                    .onErrorResume(TimeoutException.class, ex -> {
                        log.warn("ProxyFilter | event=upstream_request_timeout",
                                keyValue("routeId", matchedRoute.id()),
                                keyValue("upstream", upstream)
                        );
                        return triggerFallbackOrError(exchange, matchedRoute, cachedBody, routeTimeout);
                    })
                    .onErrorResume(CallNotPermittedException.class, ex -> {
                        log.warn("ProxyFilter | event=upstream_circuit_breaker_open",
                                keyValue("routeId", matchedRoute.id()),
                                keyValue("upstream", upstream)
                        );
                        return triggerFallbackOrError(exchange, matchedRoute, cachedBody, routeTimeout);
                    })
                    .onErrorResume(ex -> !(ex instanceof ResponseStatusException), ex -> {
                        log.warn("ProxyFilter | event=upstream_failure",
                                keyValue("routeId", matchedRoute.id()),
                                keyValue("upstream", upstream),
                                keyValue("error", ex.getMessage())
                        );
                        return triggerFallbackOrError(exchange, matchedRoute, cachedBody, routeTimeout);
                    });
        });
    }

    private Mono<Void> executeProxy(ServerWebExchange exchange, URI targetUri, Flux<DataBuffer> bodyFlux, Duration timeout, CircuitBreaker cb) {
        return webClient
                .method(exchange.getRequest().getMethod())
                .uri(targetUri)
                .headers(h -> {
                    h.addAll(exchange.getRequest().getHeaders());
                    h.remove("X-API-Key");
                    h.remove("Host");
                })
                .body(BodyInserters.fromDataBuffers(bodyFlux))
                .exchangeToMono(clientResponse -> {
                    exchange.getResponse().setStatusCode(clientResponse.statusCode());
                    clientResponse.headers().asHttpHeaders().forEach((key, values) -> {
                        if (!key.equalsIgnoreCase("Transfer-Encoding") &&
                                !key.equalsIgnoreCase("Content-Length")) {
                            exchange.getResponse().getHeaders().addAll(key, values);
                        }
                    });

                    return exchange.getResponse().writeWith(
                            clientResponse.bodyToFlux(DataBuffer.class).timeout(timeout)
                    );
                })
                .timeout(timeout)
                .transformDeferred(CircuitBreakerOperator.of(cb));
    }

    private Mono<Void> triggerFallbackOrError(ServerWebExchange exchange, Route matchedRoute, Flux<DataBuffer> cachedBody, Duration routeTimeout) {
        if (matchedRoute.fallbackUrl() == null || matchedRoute.fallbackUrl().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable"));
        }

        URI originalUri = exchange.getRequest().getURI();
        URI fallbackUri = UriComponentsBuilder
                .fromUriString(matchedRoute.fallbackUrl())
                .replacePath(originalUri.getPath())
                .replaceQuery(originalUri.getQuery())
                .build(true)
                .toUri();
        String fallback = fallbackUri.toString();

        CircuitBreaker fallbackCircuitBreaker = circuitBreakerRegistry.circuitBreaker("fallback-" + matchedRoute.id());

        log.info("ProxyFilter | event=attempting_fallback_proxy",
                keyValue("routeId", matchedRoute.id()),
                keyValue("fallbackUrl", fallback)
        );

        return executeProxy(exchange, fallbackUri, cachedBody, routeTimeout, fallbackCircuitBreaker)
                .onErrorResume(TimeoutException.class, ex -> {
                    log.warn("ProxyFilter | event=fallback_request_timeout",
                            keyValue("routeId", matchedRoute.id()),
                            keyValue("fallbackUrl", fallback)
                    );
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fallback request timed out"));
                })
                .onErrorResume(CallNotPermittedException.class, ex -> {
                    log.warn("ProxyFilter | event=fallback_circuit_breaker_open",
                            keyValue("routeId", matchedRoute.id()),
                            keyValue("fallbackUrl", fallback)
                    );
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fallback circuit breaker open"));
                })
                .onErrorResume(ex -> !(ex instanceof ResponseStatusException), ex -> {
                    log.warn("ProxyFilter | event=fallback_failure",
                            keyValue("routeId", matchedRoute.id()),
                            keyValue("fallbackUrl", fallback),
                            keyValue("error", ex.getMessage())
                    );
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Fallback destination failure"));
                });
    }
}
