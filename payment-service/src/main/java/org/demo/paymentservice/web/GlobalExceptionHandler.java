package org.demo.paymentservice.web;

import org.demo.paymentservice.order.OrderNotFoundException;
import org.demo.paymentservice.payment.OrderNotPayableException;
import org.demo.paymentservice.payment.PaymentNotFoundException;
import org.demo.paymentservice.payment.PaymentProcessingException;
import org.demo.paymentservice.payment.PaymentIdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({PaymentNotFoundException.class, OrderNotFoundException.class})
    ResponseEntity<ApiError> handleNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(OrderNotPayableException.class)
    ResponseEntity<ApiError> handleOrderNotPayable(OrderNotPayableException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(PaymentIdempotencyConflictException.class)
    ResponseEntity<ApiError> handleIdempotencyConflict(PaymentIdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(PaymentProcessingException.class)
    ResponseEntity<ApiError> handleProcessing(PaymentProcessingException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(fieldError -> details.put(fieldError.getField(), fieldError.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for request parameter", Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                details
        ));
    }
}
