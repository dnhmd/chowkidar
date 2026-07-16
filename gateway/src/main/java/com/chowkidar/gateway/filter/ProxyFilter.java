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
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(4)
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

            URI upstreamUri = UriComponentsBuilder
                    .fromUriString(matchedRoute.upstreamUrl())
                    .replacePath(originalUri.getPath())
                    .replaceQuery(originalUri.getQuery())
                    .build(true)
                    .toUri();

            String upstream = upstreamUri.toString();
            CircuitBreaker upstreamCircuitBreaker = circuitBreakerRegistry.circuitBreaker("upstream-" + matchedRoute.id());

            return webClient
                    .method(exchange.getRequest().getMethod())
                    .uri(upstreamUri)
                    .headers(h -> {
                        h.addAll(exchange.getRequest().getHeaders());
                        h.remove("X-API-Key");
                        h.remove("Host");
                    })
                    .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                    .exchangeToMono(clientResponse -> {
                        exchange.getResponse().setStatusCode(clientResponse.statusCode());
                        exchange.getResponse().getHeaders().addAll(clientResponse.headers().asHttpHeaders());

                        return exchange.getResponse().writeWith(clientResponse.bodyToFlux(DataBuffer.class).timeout(routeTimeout))
                                .doOnSuccess(v -> log.info("ProxyFilter | event=proxy_success",
                                        keyValue("tenantId", tenantId),
                                        keyValue("upstream", upstream),
                                        keyValue("method", httpMethod),
                                        keyValue("statusCode", clientResponse.statusCode().value())
                                ));
                    })
                    .timeout(routeTimeout)
//                    .doOnError(TimeoutException.class, ex -> log.error("ProxyFilter | event=timeout_operator_intercepted",
//                            keyValue("routeId", matchedRoute.id()),
//                            keyValue("upstream", upstream),
//                            keyValue("exceptionClass", ex.getClass().getName())
//                    ))
                    .transformDeferred(CircuitBreakerOperator.of(upstreamCircuitBreaker))
                    .onErrorResume(TimeoutException.class, ex -> {
                        log.warn("ProxyFilter | event=server_request_timeout",
                                keyValue("routeId", matchedRoute.id()),
                                keyValue("upstream", upstream)
                        );
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream request timed out"));
                    })
                    .onErrorResume(CallNotPermittedException.class, ex -> {
                        log.warn("ProxyFilter | event=circuit_breaker_open",
                                keyValue("routeId", matchedRoute.id()),
                                keyValue("upstream", upstream)
                        );
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream circuit breaker open"));
                    })
                    .doOnError(ex -> {
                        if (!(ex instanceof ResponseStatusException)) {
                            log.warn("ProxyFilter | event=upstream_failure",
                                    keyValue("routeId", matchedRoute.id()),
                                    keyValue("upstream", upstream),
                                    keyValue("error", ex.getMessage())
                            );
                        }
                    })
                    .onErrorMap(ex -> !(ex instanceof ResponseStatusException), ex ->
                            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable"));
        });
    }
}
