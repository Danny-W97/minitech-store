package org.demo.chatservice.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ChatException.class)
    ResponseEntity<ApiError> chat(ChatException exception, HttpServletRequest request) {
        return error(exception.status().value(), exception.status().getReasonPhrase(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return error(400, "Bad Request", "Dane wiadomości czatu są nieprawidłowe.", request);
    }

    private ResponseEntity<ApiError> error(int status, String error, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status, error, message, request.getRequestURI()));
    }

    record ApiError(Instant timestamp, int status, String error, String message, String path) {}
}
