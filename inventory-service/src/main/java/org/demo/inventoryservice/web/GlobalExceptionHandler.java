package org.demo.inventoryservice.web;

import org.demo.inventoryservice.inventory.DuplicateInventoryItemException;
import org.demo.inventoryservice.inventory.InsufficientStockException;
import org.demo.inventoryservice.inventory.InventoryItemNotFoundException;
import org.demo.inventoryservice.inventory.InventoryReservationConflictException;
import org.demo.inventoryservice.inventory.InventoryReservationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({InventoryItemNotFoundException.class, InventoryReservationNotFoundException.class})
    ResponseEntity<ApiError> handleNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler({
            DuplicateInventoryItemException.class,
            InsufficientStockException.class,
            InventoryReservationConflictException.class
    })
    ResponseEntity<ApiError> handleConflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(fieldError -> details.put(fieldError.getField(), fieldError.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleMalformedRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request", Map.of());
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
