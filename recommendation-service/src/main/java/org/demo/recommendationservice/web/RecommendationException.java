package org.demo.recommendationservice.web;

import org.springframework.http.HttpStatus;

public class RecommendationException extends RuntimeException {
    private final HttpStatus status;

    public RecommendationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
