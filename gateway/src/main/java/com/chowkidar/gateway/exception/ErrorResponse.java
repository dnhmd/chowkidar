package com.chowkidar.gateway.exception;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        String path,
        Integer status,
        String error,
        String message,
        String requestId
) {
}
