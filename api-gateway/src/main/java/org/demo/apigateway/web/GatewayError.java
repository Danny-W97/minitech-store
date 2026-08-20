package org.demo.apigateway.web;

import java.time.Instant;

public record GatewayError(
        Instant timestamp,
        int status,
        String error,
        String message
) {
}
