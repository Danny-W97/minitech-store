package org.demo.deliveryservice.delivery;

import java.time.LocalDate;

public class DeliveryDateUnavailableException extends RuntimeException {

    public DeliveryDateUnavailableException(String methodCode, LocalDate requestedDate) {
        super("Date %s is not available for delivery method %s".formatted(requestedDate, methodCode));
    }
}
