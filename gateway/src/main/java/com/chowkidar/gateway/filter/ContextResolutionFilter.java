package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.context.service.ContextService;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(1)
public class ContextResolutionFilter implements WebFilter {

    private final ContextService contextService;

    public ContextResolutionFilter(ContextService contextService) {
        this.contextService = contextService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.isManagementPath(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing API Key"));
        }

        return contextService.resolve(apiKey)
                .switchIfEmpty(Mono.defer(() -> Mono.error(
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API Key")
                )))
                .flatMap(tenantContext -> chain.filter(exchange)
                        .contextWrite(context -> context.put(TenantContext.class, tenantContext))
                );
    }
}
