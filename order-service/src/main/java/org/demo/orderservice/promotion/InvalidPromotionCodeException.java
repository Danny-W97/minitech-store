package org.demo.orderservice.promotion;

public class InvalidPromotionCodeException extends RuntimeException {
    public InvalidPromotionCodeException(String message) { super(message); }
}
