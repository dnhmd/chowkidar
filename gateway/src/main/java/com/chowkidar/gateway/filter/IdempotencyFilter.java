package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Route;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.reactivestreams.Publisher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(4)
public class IdempotencyFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final ObjectMapper objectMapper;
    private final int ttlMinutes;
    private final int lockTtlSeconds;

    public IdempotencyFilter(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${chowkidar.idempotency.ttl-minutes:30}") int ttlMinutes,
            @Value("${chowkidar.idempotency.lock-ttl-seconds:30}") int lockTtlSeconds
    ) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.objectMapper = objectMapper;
        this.ttlMinutes = ttlMinutes;
        this.lockTtlSeconds = lockTtlSeconds;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.shouldBypassFilters(exchange.getRequest().getURI().getPath())) {
            return chain.filter(exchange);
        }

        String method = exchange.getRequest().getMethod().toString();
        List<String> idempotentMethods = List.of("POST", "PUT", "PATCH");
        if (!idempotentMethods.contains(method)) {
            return chain.filter(exchange);
        }

        return Mono.deferContextual(contextView -> {
            TenantContext tenantContext = contextView.getOrEmpty(TenantContext.class)
                    .map(tenantContextObject -> (TenantContext) tenantContextObject)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context missing"));

            Route matchedRoute = contextView.getOrEmpty(Route.class)
                    .map(matchedRouteObject -> (Route) matchedRouteObject)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Matched route missing"));

            Tenant tenant = tenantContext.tenant();
            String requestedPath = exchange.getRequest().getURI().getPath();
            String idempotencyKey = exchange.getRequest().getHeaders().getFirst("X-Idempotency-Key");

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                if (matchedRoute.requiresIdempotency()) {
                    log.warn("IdempotencyFilter | event=missing_idempotency_key",
                            keyValue("tenantId", tenant.id()),
                            keyValue("path", requestedPath)
                    );
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Idempotency key required for this route"));
                }
                return chain.filter(exchange);
            }

            String redisKey = "idempotency:" + tenant.id() + ":" + idempotencyKey;

            return reactiveRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "PROCESSING", Duration.ofSeconds(lockTtlSeconds))
                    .flatMap(acquired -> {
                        if (acquired) {
                            return handleFirstRequest(exchange, chain, redisKey);
                        } else {
                            return handleDuplicateRequest(exchange, redisKey, tenant.id(), idempotencyKey);
                        }
                    });
        });
    }

    private Mono<Void> handleFirstRequest(ServerWebExchange exchange, WebFilterChain chain, String redisKey) {
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return Flux.from(body)
                        .collectList()
                        .flatMap(dataBuffers -> {
                            int totalSize = dataBuffers.stream()
                                    .mapToInt(DataBuffer::readableByteCount)
                                    .sum();
                            byte[] bytes = new byte[totalSize];
                            int offset = 0;
                            for (DataBuffer buffer : dataBuffers) {
                                int count = buffer.readableByteCount();
                                buffer.read(bytes, offset, count);
                                offset += count;
                                DataBufferUtils.release(buffer);
                            }

                            String responseBody = new String(bytes, StandardCharsets.UTF_8);
                            int statusCode = exchange.getResponse().getStatusCode() != null
                                    ? exchange.getResponse().getStatusCode().value()
                                    : 200;

                            String stored = "{\"status\":\"COMPLETED\",\"statusCode\":%d,\"body\":%s}"
                                    .formatted(statusCode, responseBody);

                            if (statusCode >= 200 && statusCode < 300) {
                                return reactiveRedisTemplate.opsForValue()
                                        .set(redisKey, stored, Duration.ofMinutes(ttlMinutes))
                                        .then(super.writeWith(
                                                Mono.just(exchange.getResponse()
                                                        .bufferFactory()
                                                        .wrap(bytes))
                                        ));
                            } else {
                                return reactiveRedisTemplate.delete(redisKey)
                                        .then(super.writeWith(
                                                Mono.just(exchange.getResponse()
                                                        .bufferFactory()
                                                        .wrap(bytes))
                                        ));
                            }
                        });
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    private Mono<Void> handleDuplicateRequest(ServerWebExchange exchange, String redisKey, UUID tenantId, String idempotencyKey) {
        return reactiveRedisTemplate.opsForValue().get(redisKey)
                .flatMap(cachedValue -> {
                    if ("PROCESSING".equals(cachedValue)) {
                        log.warn("IdempotencyFilter | event=idempotency_conflict",
                                keyValue("tenantId", tenantId),
                                keyValue("idempotencyKey", idempotencyKey)
                        );
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "Duplicate request in progress"));
                    }

                    try {
                        JsonNode node = objectMapper.readTree(cachedValue);
                        int statusCode = node.get("statusCode").asInt();
                        String body = objectMapper.writeValueAsString(node.get("body"));

                        log.debug("IdempotencyFilter | event=cache_hit_replay",
                                keyValue("tenantId", tenantId),
                                keyValue("idempotencyKey", idempotencyKey),
                                keyValue("statusCode", statusCode)
                        );

                        ServerHttpResponse response = exchange.getResponse();
                        response.setStatusCode(HttpStatus.valueOf(statusCode));
                        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        response.getHeaders().set("X-Idempotent-Replay", "true");

                        DataBuffer buffer = response.bufferFactory()
                                .wrap(body.getBytes(StandardCharsets.UTF_8));
                        return response.writeWith(Mono.just(buffer));
                    } catch (Exception e) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Failed to replay cached response"));
                    }
                });
    }
}