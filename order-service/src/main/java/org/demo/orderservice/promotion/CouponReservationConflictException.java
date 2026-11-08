package org.demo.orderservice.promotion;

public class CouponReservationConflictException extends org.springframework.web.server.ResponseStatusException {
    public CouponReservationConflictException() { super(org.springframework.http.HttpStatus.CONFLICT,"Coupon reservation or capacity requires reconciliation"); }
}
