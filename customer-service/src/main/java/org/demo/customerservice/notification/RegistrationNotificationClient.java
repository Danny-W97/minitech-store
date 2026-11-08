package org.demo.customerservice.notification;

import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RegistrationNotificationClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public RegistrationNotificationClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.notification.base-url:http://localhost:18086}") String notificationBaseUrl
            , ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder
                .baseUrl(notificationBaseUrl)
                .build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public void sendConfirmation(Customer customer, String confirmationLink) {
        send(customer, "CUSTOMER_REGISTERED", "Confirm your MiniTech Store account",
                "Your MiniTech Store account is almost ready. Confirm it within 5 minutes by opening this link: "
                        + confirmationLink, customer.getId());
    }

    public void sendPasswordReset(Customer customer, String resetLink, Long notificationSourceId) {
        send(customer, "PASSWORD_RESET_REQUESTED", "Reset your MiniTech Store password",
                "A password reset was requested for your MiniTech Store account. The link is valid for 15 minutes: "
                        + resetLink, notificationSourceId);
    }

    private void send(Customer customer, String eventType, String subject, String message, Long sourceId) {
        resilientHttpExecutor.execute("notification", () -> restClient.post()
                .uri("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateNotificationRequest(
                        eventType,
                        sourceId,
                        customer.getEmail(),
                        "EMAIL",
                        subject,
                        message,
                        false,customer.getId()
                ))
                .retrieve()
                .toBodilessEntity());
    }

    private record CreateNotificationRequest(
            String eventType,
            Long sourceId,
            String recipient,
            String channel,
            String subject,
            String message,
            boolean simulateFailure,Long customerId
    ) {
    }
}
