package org.demo.orderservice.web;

import org.demo.orderservice.order.InvalidOrderStatusTransitionException;
import org.demo.orderservice.order.OrderNotFoundException;
import org.demo.orderservice.order.ProductInactiveException;
import org.demo.orderservice.product.ProductNotFoundException;
import org.demo.orderservice.product.ProductServiceException;
import org.demo.orderservice.promotion.InvalidPromotionCodeException;
import org.demo.orderservice.promotion.PromotionServiceException;
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

    @ExceptionHandler({OrderNotFoundException.class, ProductNotFoundException.class})
    ResponseEntity<ApiError> handleNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ProductInactiveException.class)
    ResponseEntity<ApiError> handleInactive(ProductInactiveException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidOrderStatusTransitionException.class)
    ResponseEntity<ApiError> handleInvalidTransition(InvalidOrderStatusTransitionException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ProductServiceException.class)
    ResponseEntity<ApiError> handleProductService(ProductServiceException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidPromotionCodeException.class)
    ResponseEntity<ApiError> handleInvalidPromotionCode(InvalidPromotionCodeException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(PromotionServiceException.class)
    ResponseEntity<ApiError> handlePromotionService(PromotionServiceException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
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
