package com.chowkidar.gateway.filter;

import com.chowkidar.gateway.config.GatewayPaths;
import com.chowkidar.gateway.context.model.Tenant;
import com.chowkidar.gateway.context.model.TenantContext;
import com.chowkidar.gateway.persistence.entity.TenantIpRuleEntity;
import com.chowkidar.gateway.persistence.repositories.TenantIpRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(2)
public class IpFilterFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(IpFilterFilter.class);

    private final long cacheTtlMs;

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final TenantIpRuleRepository tenantIpRuleRepository;

    public IpFilterFilter(
            @Value("${chowkidar.ip-filter.cache-ttl-ms:1800000}") long cacheTtlMs,
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            TenantIpRuleRepository tenantIpRuleRepository
    ) {
        this.cacheTtlMs = cacheTtlMs;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.tenantIpRuleRepository = tenantIpRuleRepository;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (GatewayPaths.shouldBypassFilters(exchange.getRequest().getURI().getPath()))
            return chain.filter(exchange);

        return Mono.deferContextual(contextView -> {
            TenantContext tenantContext = contextView.getOrEmpty(TenantContext.class)
                    .map(tenantContextObject -> (TenantContext) tenantContextObject)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context missing"));

            Tenant tenant = tenantContext.tenant();
            String ipAddress = getClientIpAddress(exchange);
            String redisKey = "iprule:" + tenant.id().toString() + ":" + ipAddress;

            return reactiveRedisTemplate.opsForValue().get(redisKey)
                    .flatMap(cacheAction -> {
                        if ("ALLOW".equals(cacheAction)) {
                            return chain.filter(exchange);
                        } else {
                            logIpBlock(tenant, ipAddress);
                            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "IP Blocked"));
                        }
                    })
                    .switchIfEmpty(Mono.defer(() -> tenantIpRuleRepository.findByTenantId(tenant.id())
                                    .collectList()
                                    .flatMap(rules -> {
                                        String finalDecision = evaluateIpRules(rules, ipAddress);

                                        return reactiveRedisTemplate.opsForValue()
                                                .set(redisKey, finalDecision, Duration.ofMillis(cacheTtlMs))
                                                .then(Mono.defer(() -> {
                                                    if ("ALLOW".equals(finalDecision)) {
                                                        return chain.filter(exchange);
                                                    } else {
                                                        logIpBlock(tenant, ipAddress);
                                                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "IP Blocked"));
                                                    }
                                                }));
                                    })
                    ));
        });
    }

    private String evaluateIpRules(List<TenantIpRuleEntity> rules, String ipAddress) {
        if (rules == null || rules.isEmpty()) {
            return "ALLOW";
        }

        boolean hasAllowRules = rules.stream()
                .anyMatch(r -> "ALLOW".equalsIgnoreCase(r.action));
        boolean isExplicitlyBlocked = rules.stream()
                .anyMatch(r -> "BLOCK".equalsIgnoreCase(r.action) && r.ipAddress.equals(ipAddress));
        boolean isExplicitlyAllowed = rules.stream()
                .anyMatch(r -> "ALLOW".equalsIgnoreCase(r.action) && r.ipAddress.equals(ipAddress));

        if (isExplicitlyBlocked) {
            return "BLOCK";
        }

        if (hasAllowRules) {
            return isExplicitlyAllowed ? "ALLOW" : "BLOCK";
        }

        return "ALLOW";
    }

    private void logIpBlock(Tenant tenant, String ipAddress) {
        log.warn("IpFilterFilter | event=ip_blocked",
                keyValue("tenantId", tenant.id()),
                keyValue("ipAddress", ipAddress)
        );
    }

    private static String getClientIpAddress(ServerWebExchange exchange) {
        String ipAddress = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            ipAddress = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
        } else {
            ipAddress = ipAddress.split(",")[0].trim();
        }

        if ("0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)) {
            ipAddress = "127.0.0.1";
        }

        if (Objects.equals(ipAddress, "unknown")) {
            log.warn("IpFilterFilter | event=unknown_ip");
        }

        return ipAddress;
    }
}
