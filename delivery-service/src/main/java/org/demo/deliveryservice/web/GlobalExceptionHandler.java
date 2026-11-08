package org.demo.deliveryservice.web;

import org.demo.deliveryservice.delivery.DeliveryMethodNotFoundException;
import org.demo.deliveryservice.delivery.InvalidPickupLocationException;
import org.demo.deliveryservice.delivery.DeliveryDateUnavailableException;
import org.demo.deliveryservice.delivery.InvalidShipmentStatusException;
import org.demo.deliveryservice.delivery.ShipmentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidPickupLocationException.class)
    ResponseEntity<ApiError> handlePickupLocation(InvalidPickupLocationException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DeliveryMethodNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(DeliveryMethodNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ShipmentNotFoundException.class)
    ResponseEntity<ApiError> handleShipmentNotFound(ShipmentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidShipmentStatusException.class)
    ResponseEntity<ApiError> handleInvalidShipmentStatus(InvalidShipmentStatusException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DeliveryDateUnavailableException.class)
    ResponseEntity<ApiError> handleDateUnavailable(DeliveryDateUnavailableException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(fieldError -> details.put(fieldError.getField(), fieldError.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", details);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, details
        ));
    }
}
