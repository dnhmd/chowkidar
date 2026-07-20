package com.chowkidar.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
@Order(-1)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {

        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }

        HttpStatus httpStatus;
        String message;

        if (ex instanceof WebExchangeBindException webExchangeBindException) {
            httpStatus = HttpStatus.BAD_REQUEST;
            message = webExchangeBindException.getBindingResult().getFieldErrors().stream()
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .collect(Collectors.joining(", "));
        } else if (ex instanceof ResponseStatusException responseStatusException) {
            httpStatus = HttpStatus.valueOf(responseStatusException.getStatusCode().value());
            message = responseStatusException.getReason();
        } else {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected error occurred.";

            log.error("GlobalExceptionHandler | event=internal_server_error | Error: {}", ex.getMessage(),
                    keyValue("path", exchange.getRequest().getPath()),
                    keyValue("method", exchange.getRequest().getMethod().name()),
                    keyValue("error", ex.getMessage()));
        }

        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                exchange.getRequest().getURI().getPath(),
                httpStatus.value(),
                HttpStatus.valueOf(httpStatus.value()).getReasonPhrase(),
                message,
                exchange.getRequest().getId()
        );

        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
