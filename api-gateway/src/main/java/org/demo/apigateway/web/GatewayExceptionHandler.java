package org.demo.apigateway.web;

import org.demo.apigateway.proxy.GatewayUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(GatewayUnavailableException.class)
    ResponseEntity<GatewayError> handleUnavailable(GatewayUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new GatewayError(
                Instant.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                exception.getMessage()
        ));
    }
}
