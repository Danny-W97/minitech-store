package org.demo.orderservice.promotion;

public class PromotionServiceException extends RuntimeException {
    public PromotionServiceException(String message) { super(message); }
    public PromotionServiceException(String message, Throwable cause) { super(message, cause); }
}
