package org.demo.billingservice.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Return actionable preflight errors before any archive bytes have been committed. */
@RestControllerAdvice
public class ResponseStatusAdvice {
    public record Error(int status, String error, String message) {}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Error> status(ResponseStatusException exception) {
        var status=HttpStatus.valueOf(exception.getStatusCode().value());
        var response=ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore());
        if (status==HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After","1");
        return response.body(new Error(status.value(),status.getReasonPhrase(),exception.getReason()==null ? status.getReasonPhrase() : exception.getReason()));
    }
}
