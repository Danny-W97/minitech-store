package org.demo.returnsservice.returns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
public class ReturnBillingClient {
    public record InvoiceItem(Long productId, String sku, String productName, Integer quantity, BigDecimal unitPrice, BigDecimal totalPrice) {}
    public record InvoiceSnapshot(Long id, Long orderId, String invoiceNumber, BigDecimal grossAmount, BigDecimal netAmount, BigDecimal vatAmount, List<InvoiceItem> items, String currency, String status) {}
    public record BalanceResponse(Long invoiceId, BigDecimal grossAmount, BigDecimal netAmount, BigDecimal vatAmount, long version) {}
    public record CorrectionRequest(String reason, BigDecimal correctionGrossAmount, String idempotencyKey, Long expectedVersion) {}
    public record CorrectionResponse(Long id, Long invoiceId, String correctionNumber, BigDecimal originalGross, BigDecimal correctionGross, BigDecimal finalGross, Long balanceVersion) {}

    private final RestClient client;

    public ReturnBillingClient(RestClient.Builder builder,
                               @Value("${services.billing.base-url:${BILLING_SERVICE_URL:${PLATFORM_BILLING_URL:http://localhost:18095}}}") String base) {
        this.client = builder.baseUrl(base).build();
    }

    public Optional<InvoiceSnapshot> byOrder(Long orderId) {
        try {
            var invoice = client.get()
                    .uri("/invoices/order/{orderId}", orderId)
                    .retrieve()
                    .body(InvoiceSnapshot.class);
            return Optional.ofNullable(invoice);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return Optional.empty();
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()), "BILLING_INVOICE_LOOKUP_FAILED");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_SERVICE_UNAVAILABLE");
        }
    }

    public BalanceResponse balance(Long invoiceId) {
        try {
            var balance = client.get()
                    .uri("/invoices/{id}/correction-balance", invoiceId)
                    .retrieve()
                    .body(BalanceResponse.class);
            if (balance == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_BALANCE_UNAVAILABLE");
            return balance;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()), "BILLING_BALANCE_FAILED");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_SERVICE_UNAVAILABLE");
        }
    }

    public CorrectionResponse issueCorrection(Long invoiceId, BigDecimal correctionDelta, String idempotencyKey, long expectedVersion, String reason) {
        try {
            var body = new CorrectionRequest(reason, correctionDelta, idempotencyKey, expectedVersion);
            var response = client.post()
                    .uri("/invoices/{id}/corrections", invoiceId)
                    .body(body)
                    .retrieve()
                    .body(CorrectionResponse.class);
            if (response == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_CORRECTION_NO_RESPONSE");
            return response;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()), "BILLING_CORRECTION_FAILED: " + error.getResponseBodyAsString());
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_SERVICE_UNAVAILABLE");
        }
    }
}
