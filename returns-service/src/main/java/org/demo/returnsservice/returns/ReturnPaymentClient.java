package org.demo.returnsservice.returns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class ReturnPaymentClient {
    public record PaymentSnapshot(
            Long id,
            Long orderId,
            BigDecimal amount,
            String currency,
            String status,
            String paymentMethod,
            String providerReference,
            String collectionReference,
            String checkoutUrl,
            String checkoutSessionId
    ) {
        public PaymentSnapshot(Long id, Long orderId, BigDecimal amount, String currency, String status, String paymentMethod, String checkoutSessionId) {
            this(id, orderId, amount, currency, status, paymentMethod, null, null, null, checkoutSessionId);
        }
    }
    public record RefundRequest(BigDecimal amount, String idempotencyKey, String reason) {}
    public record RefundResponse(Long id, Long paymentId, Long orderId, BigDecimal amount, String currency, String status, String providerRefundId, String businessOutcome) {}

    private final RestClient client;

    public ReturnPaymentClient(RestClient.Builder builder,
                               @Value("${services.payment.base-url:${PAYMENT_SERVICE_URL:${PLATFORM_PAYMENT_URL:http://localhost:18084}}}") String base) {
        this.client = builder.baseUrl(base).build();
    }

    public Optional<PaymentSnapshot> byOrder(Long orderId) {
        try {
            var payment = client.get()
                    .uri("/payments/order/{orderId}", orderId)
                    .retrieve()
                    .body(PaymentSnapshot.class);
            return Optional.ofNullable(payment);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return Optional.empty();
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()), "PAYMENT_LOOKUP_FAILED");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE");
        }
    }

    public RefundResponse refund(Long paymentId, BigDecimal amount, String idempotencyKey, String reason) {
        try {
            var body = new RefundRequest(amount, idempotencyKey, reason);
            var response = client.post()
                    .uri("/payments/{paymentId}/refunds", paymentId)
                    .body(body)
                    .retrieve()
                    .body(RefundResponse.class);
            if (response == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_REFUND_NO_RESPONSE");
            return response;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(HttpStatus.valueOf(error.getStatusCode().value()), "PAYMENT_REFUND_FAILED: " + error.getResponseBodyAsString());
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE");
        }
    }
}
