package org.demo.reviewservice.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ReviewException.class)
    ResponseEntity<ApiError> review(ReviewException exception, HttpServletRequest request) {
        return error(exception.status().value(), exception.status().getReasonPhrase(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> validation(Exception exception, HttpServletRequest request) {
        return error(400, "Bad Request", "Dane opinii są nieprawidłowe.", request);
    }

    private ResponseEntity<ApiError> error(int status, String error, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status, error, message, request.getRequestURI()));
    }

    record ApiError(Instant timestamp, int status, String error, String message, String path) {}
}
