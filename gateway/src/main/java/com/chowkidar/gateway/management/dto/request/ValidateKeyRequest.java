package com.chowkidar.gateway.management.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ValidateKeyRequest(
        @NotBlank String apiKey
) {
}
