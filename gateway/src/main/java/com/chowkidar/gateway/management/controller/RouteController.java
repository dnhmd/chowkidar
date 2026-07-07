package com.chowkidar.gateway.management.controller;

import com.chowkidar.gateway.management.dto.request.CreateRouteRequest;
import com.chowkidar.gateway.management.dto.request.UpdateRouteRateRequest;
import com.chowkidar.gateway.management.dto.request.UpdateRouteUpstreamRequest;
import com.chowkidar.gateway.management.dto.response.RouteResponse;
import com.chowkidar.gateway.management.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/management/tenants/{tenantId}/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping
    public Mono<ResponseEntity<RouteResponse>> createRoute(@PathVariable("tenantId") UUID tenantId, @Valid @RequestBody CreateRouteRequest createRouteRequest) {
        return routeService.create(tenantId, createRouteRequest)
                .map(routeResponse -> ResponseEntity.status(HttpStatus.CREATED).body(routeResponse));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<RouteResponse>> getRoute(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id) {
        return routeService.getById(tenantId, id)
                .map(routeResponse -> ResponseEntity.status(HttpStatus.OK).body(routeResponse));
    }

    @GetMapping
    public Flux<RouteResponse> getAll(@PathVariable("tenantId") UUID tenantId) {
        return routeService.getAllByTenant(tenantId);
    }

    @PatchMapping("/{id}/upstream")
    public Mono<ResponseEntity<RouteResponse>> updateUpstream(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id, @Valid @RequestBody UpdateRouteUpstreamRequest updateRouteUpstreamRequest) {
        return routeService.updateUpstream(tenantId, id, updateRouteUpstreamRequest)
                .map(routeResponse -> ResponseEntity.status(HttpStatus.OK).body(routeResponse));
    }

    @PatchMapping("/{id}/rate")
    public Mono<ResponseEntity<RouteResponse>> updateRouteRate(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id, @Valid @RequestBody UpdateRouteRateRequest updateRouteRateRequest) {
        return routeService.updateRate(tenantId, id, updateRouteRateRequest)
                .map(routeResponse -> ResponseEntity.status(HttpStatus.OK).body(routeResponse));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteRoute(@PathVariable("tenantId") UUID tenantId, @PathVariable("id") UUID id) {
        return routeService.delete(tenantId, id)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
