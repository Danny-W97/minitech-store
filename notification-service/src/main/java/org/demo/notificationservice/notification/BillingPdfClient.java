package org.demo.notificationservice.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BillingPdfClient {
    private final RestClient restClient;

    public BillingPdfClient(
            @Value("${services.billing.base-url:${BILLING_SERVICE_URL:http://localhost:18095}}") String baseUrl
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public byte[] download(Long invoiceId) {
        byte[] pdf = restClient.get().uri("/invoices/{id}/pdf", invoiceId)
                .retrieve().body(byte[].class);
        if (pdf == null || pdf.length == 0) {
            throw new IllegalStateException("Billing Service returned an empty invoice PDF");
        }
        return pdf;
    }
}
