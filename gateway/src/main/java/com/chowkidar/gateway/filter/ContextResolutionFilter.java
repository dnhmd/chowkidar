package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.context.service.ContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(1)
public class ContextResolutionFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ContextResolutionFilter.class);

    private final ContextService contextService;

    public ContextResolutionFilter(ContextService contextService) {
        this.contextService = contextService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.shouldBypassFilters(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ContextResolutionFilter | event=missing_api_key",
                    keyValue("path", exchange.getRequest().getPath()),
                    keyValue("method", exchange.getRequest().getMethod().name())
            );
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing API Key"));
        }

        return contextService.resolve(apiKey)
                .doOnError(ex -> log.warn("ContextResolutionFilter | event=resolution_failed",
                            keyValue("path", exchange.getRequest().getPath()),
                            keyValue("status", ex instanceof ResponseStatusException rse ? rse.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR)
                ))
                .flatMap(tenantContext -> {
                    log.debug("ContextResolutionFilter | event=resolution_success",
                            keyValue("tenantId", tenantContext.tenant().id()),
                            keyValue("deprecated", tenantContext.isDeprecated())
                    );
                    return chain.filter(exchange).contextWrite(context -> context.put(TenantContext.class, tenantContext));
                });
    }
}
