package org.demo.paymentservice.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PositiveOrZero;
import org.demo.paymentservice.billing.BillingDataRequest;

public record CreatePaymentRequest(
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 30) String paymentMethod,
        boolean simulateFailure,
        @Email @Size(max = 255) String notificationRecipient,
        @Size(max = 100) String idempotencyKey,
        @PositiveOrZero Long customerId,
        Boolean invoiceRequested,
        @Valid BillingDataRequest billingData
) {

    public CreatePaymentRequest(Long orderId, String paymentMethod, boolean simulateFailure) {
        this(orderId, paymentMethod, simulateFailure, null, null, null, false, null);
    }

    public CreatePaymentRequest(
            Long orderId,
            String paymentMethod,
            boolean simulateFailure,
            String notificationRecipient
    ) {
        this(orderId, paymentMethod, simulateFailure, notificationRecipient, null, null, false, null);
    }

    @JsonIgnore
    @AssertTrue(message = "Dane do faktury są wymagane po wybraniu faktury")
    public boolean isInvoiceDataValid() {
        return !Boolean.TRUE.equals(invoiceRequested) || billingData != null;
    }
}
