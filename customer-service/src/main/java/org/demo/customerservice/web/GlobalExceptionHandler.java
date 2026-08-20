package org.demo.customerservice.web;

import org.demo.customerservice.customer.CustomerNotFoundException;
import org.demo.customerservice.customer.CustomerAccessDeniedException;
import org.demo.customerservice.customer.DuplicateEmailException;
import org.demo.customerservice.customer.InvalidCredentialsException;
import org.demo.customerservice.customer.InvalidVerificationTokenException;
import org.demo.customerservice.customer.PendingRegistrationException;
import org.demo.customerservice.customer.InvalidPasswordResetTokenException;
import org.demo.customerservice.auth.InvalidRefreshTokenException;
import org.demo.customerservice.auth.AccountLockedException;
import org.demo.customerservice.auth.MfaChallengeExpiredException;
import org.demo.customerservice.mfa.InvalidMfaCodeException;
import org.demo.customerservice.mfa.MfaAlreadyEnabledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ApiError> notFound(CustomerNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiError> duplicate(DuplicateEmailException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> credentials(InvalidCredentialsException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ApiError> accountLocked(AccountLockedException exception) {
        return ResponseEntity.status(423)
                .header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()))
                .body(new ApiError(
                        Instant.now(), 423, "Locked", exception.getMessage(),
                        Map.of("retryAfterSeconds", String.valueOf(exception.getRetryAfterSeconds()))
                ));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ApiError> refreshToken(InvalidRefreshTokenException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler({MfaChallengeExpiredException.class, InvalidMfaCodeException.class})
    ResponseEntity<ApiError> mfaFailure(RuntimeException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MfaAlreadyEnabledException.class)
    ResponseEntity<ApiError> mfaAlreadyEnabled(MfaAlreadyEnabledException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(CustomerAccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(CustomerAccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(PendingRegistrationException.class)
    ResponseEntity<ApiError> pendingRegistration(PendingRegistrationException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    ResponseEntity<ApiError> invalidVerification(InvalidVerificationTokenException exception) {
        return error(HttpStatus.GONE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    ResponseEntity<ApiError> invalidPasswordReset(InvalidPasswordResetTokenException exception) {
        return error(HttpStatus.GONE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(field -> details.put(field.getField(), field.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request", Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), message, details
        ));
    }
}
