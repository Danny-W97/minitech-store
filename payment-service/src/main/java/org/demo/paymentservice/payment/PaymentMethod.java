package org.demo.paymentservice.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;

public enum PaymentMethod {
    CARD, BLIK, TRANSFER, CASH_ON_DELIVERY, CASH_AT_PICKUP;

    public boolean deferred() {
        return this == CASH_ON_DELIVERY || this == CASH_AT_PICKUP;
    }

    public static PaymentMethod parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
        }
    }
}
