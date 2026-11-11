package org.demo.billingservice.billing;

import org.demo.billingservice.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceOrderVerifierTest {
    @Mock BillingOrderClient orders;
    private final BillingData buyer = new BillingData(false, "Jan Test", null, null, "Testowa 1", "00-001", "Warszawa", "Polska");
    private final InvoiceItemRequest item = new InvoiceItemRequest(7L, "SKU-7", "Produkt", 2, new BigDecimal("12.00"), new BigDecimal("24.00"));
    private IssueInvoiceRequest request(Long customer, String email, String currency, String gross, String delivery, String discount, List<InvoiceItemRequest> items) {
        return new IssueInvoiceRequest(1L, customer, email, new BigDecimal(gross), currency, new BigDecimal(delivery), new BigDecimal(discount), items, buyer);
    }
    private IssueInvoiceRequest valid() { return request(9L, "buyer@example.test", "PLN", "25.99", "3.99", "2.00", List.of(item)); }
    private BillingOrderClient.PaidOrder paid(String status) {
        return new BillingOrderClient.PaidOrder(1L, 9L, "buyer@example.test", status, new BigDecimal("25.99"), new BigDecimal("3.99"),
                new BigDecimal("2.00"), List.of(new BillingOrderClient.Item(7L, "SKU-7", "Produkt", 2, new BigDecimal("12.00"), new BigDecimal("24.00"))));
    }

    @Test void acceptsPaidShippedAndCompletedOrders() {
        for (String status : List.of("PAID", "SHIPPED", "COMPLETED")) {
            when(orders.get(1L)).thenReturn(paid(status));
            assertThat(new InvoiceOrderVerifier(orders).verify(valid())).isEqualTo(valid());
        }
    }
    @Test void anUnreconciledHistoricalPaidStatusCannotCreateAnInvoice() {
        var original=paid("PAID");
        when(orders.get(1L)).thenReturn(new BillingOrderClient.PaidOrder(original.id(),original.customerId(),original.customerEmail(),
                original.status(),original.totalPrice(),original.deliveryCost(),original.discountAmount(),original.items(),"PAID",true));
        assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(valid())).hasMessageContaining("409");
    }
    @Test void rejectsUnpaidAndCancelledOrders() {
        for (String status : List.of("NEW", "CONFIRMED", "CANCELLED", "PAYMENT_PENDING")) {
            when(orders.get(1L)).thenReturn(paid(status));
            assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(valid())).hasMessageContaining("409");
        }
    }
    @Test void shippedOrCompletedCashOrdersRemainUnpaidUntilCollectionIsRecorded() {
        for(String status:List.of("SHIPPED","COMPLETED")) {
            var order=paid(status);
            when(orders.get(1L)).thenReturn(new BillingOrderClient.PaidOrder(order.id(),order.customerId(),order.customerEmail(),status,
                    order.totalPrice(),order.deliveryCost(),order.discountAmount(),order.items(),"COLLECTION_DUE"));
            assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(valid())).hasMessageContaining("409");
        }
    }
    @Test void missingOrderAndUnavailableVerificationCannotIssueInvoice() {
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(status)).when(orders).get(1L);
            assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(valid())).hasMessageContaining(String.valueOf(status.value()));
        }
    }
    @Test void rejectsForgedOwnerEmailCurrencyAndAllFinancialFields() {
        when(orders.get(1L)).thenReturn(paid("PAID"));
        for (var request : List.of(
                request(8L, "buyer@example.test", "PLN", "25.99", "3.99", "2", List.of(item)),
                request(9L, "other@example.test", "PLN", "25.99", "3.99", "2", List.of(item)),
                request(9L, "buyer@example.test", "EUR", "25.99", "3.99", "2", List.of(item)),
                request(9L, "buyer@example.test", "PLN", "0.01", "3.99", "2", List.of(item)),
                request(9L, "buyer@example.test", "PLN", "25.99", "0", "2", List.of(item)),
                request(9L, "buyer@example.test", "PLN", "25.99", "3.99", "0", List.of(item)))) {
            assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(request)).hasMessageContaining("400");
        }
    }
    @Test void rejectsChangedMissingAndDuplicatedItems() {
        when(orders.get(1L)).thenReturn(paid("PAID"));
        var changed = new InvoiceItemRequest(7L, "SKU-7", "Produkt", 2, new BigDecimal("12.00"), new BigDecimal("23.00"));
        for (var items : List.of(List.<InvoiceItemRequest>of(), List.of(item, item), List.of(changed))) {
            assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(request(9L, "buyer@example.test", "PLN", "25.99", "3.99", "2", items)))
                    .hasMessageContaining("400");
        }
    }
    @Test void rejectsInconsistentAuthoritativeOrderInsteadOfRoundingItToFit() {
        var broken = new BillingOrderClient.PaidOrder(1L, 9L, "buyer@example.test", "PAID", new BigDecimal("25.99"),
                new BigDecimal("3.99"), new BigDecimal("2.00"), List.of(new BillingOrderClient.Item(7L, "SKU-7", "Produkt", 2,
                new BigDecimal("12.00"), new BigDecimal("23.00"))));
        when(orders.get(1L)).thenReturn(broken);
        var request = request(9L, "buyer@example.test", "PLN", "25.99", "3.99", "2", List.of(
                new InvoiceItemRequest(7L, "SKU-7", "Produkt", 2, new BigDecimal("12"), new BigDecimal("23"))));
        assertThatThrownBy(() -> new InvoiceOrderVerifier(orders).verify(request)).hasMessageContaining("400");
    }
}
