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
    @ExceptionHandler(org.demo.paymentservice.payment.StripeReviewRequiredException.class)
    ResponseEntity<ApiError> handleStripeReview(org.demo.paymentservice.payment.StripeReviewRequiredException exception) {
        return error(HttpStatus.CONFLICT,"Stripe operation requires reconciliation",Map.of("code",exception.getMessage()));
    }
    @ExceptionHandler({org.demo.paymentservice.inventory.InventoryServiceException.class,
            org.demo.paymentservice.order.OrderServiceException.class,org.demo.paymentservice.delivery.DeliveryServiceException.class,
            org.demo.paymentservice.promotion.PromotionServiceException.class})
    ResponseEntity<ApiError> handleDependency(RuntimeException exception) {
        for(Throwable cause=exception;cause!=null;cause=cause.getCause())
            if(cause instanceof org.springframework.web.client.RestClientResponseException http && http.getStatusCode().value()==409)
                return error(HttpStatus.CONFLICT,"Dependent service rejected the requested state",Map.of());
        return error(HttpStatus.SERVICE_UNAVAILABLE,"Required service is unavailable or did not confirm the operation",Map.of());
    }

    @ExceptionHandler(org.demo.platformsecurity.RequestBudget.Exceeded.class)
    ResponseEntity<ApiError> handleDeadline(org.demo.platformsecurity.RequestBudget.Exceeded exception) {
        return error(HttpStatus.GATEWAY_TIMEOUT,exception.getMessage(),Map.of());
    }

    @ExceptionHandler(org.demo.paymentservice.payment.PaymentInProgressException.class)
    ResponseEntity<ApiError> handleInProgress(org.demo.paymentservice.payment.PaymentInProgressException exception) {
        var result=error(HttpStatus.CONFLICT,exception.getReason(),Map.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).header("Retry-After","1")
                .header("Cache-Control","no-store").body(result.getBody());
    }

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
