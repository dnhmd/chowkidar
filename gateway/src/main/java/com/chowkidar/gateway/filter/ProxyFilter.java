package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Route;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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
import reactor.core.publisher.Mono;

@Component
@Order(4)
public class ProxyFilter implements WebFilter {

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ProxyFilter(WebClient webClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = webClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.isManagementPath(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);
        return Mono.deferContextual(contextView -> {
            Route matchedRoute = contextView.getOrEmpty(Route.class)
                    .map(matchedRouteObject -> (Route) matchedRouteObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Matched route missing"));

            String upstream = matchedRoute.upstreamUrl() + matchedRoute.path();

            CircuitBreaker upstreamCircuitBreaker = circuitBreakerRegistry.circuitBreaker("upstream-" + matchedRoute.id());

            return webClient
                    .method(exchange.getRequest().getMethod())
                    .uri(upstream)
                    .headers(h -> {
                        h.addAll(exchange.getRequest().getHeaders());
                        h.remove("X-API-Key");
                        h.remove("Host");
                    })
                    .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                    .exchangeToMono(clientResponse -> {
                        exchange.getResponse().setStatusCode(clientResponse.statusCode());
                        exchange.getResponse().getHeaders().addAll(clientResponse.headers().asHttpHeaders());
                        return exchange.getResponse().writeWith(clientResponse.bodyToFlux(DataBuffer.class));
                    })
                    .transformDeferred(CircuitBreakerOperator.of(upstreamCircuitBreaker))
                    .onErrorMap(CallNotPermittedException.class, ex ->
                            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable"));
        });
    }
}
