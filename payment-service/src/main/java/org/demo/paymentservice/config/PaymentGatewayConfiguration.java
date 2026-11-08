package org.demo.paymentservice.config;

import org.demo.paymentservice.payment.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class PaymentGatewayConfiguration {
    @Bean
    PaymentGateway paymentGateway(Environment environment, @Value("${payment.provider:disabled}") String provider,
            @Value("${payment.stripe.secret-key:}") String stripeSecret,
            @Value("${payment.stripe.webhook-secret:}") String stripeWebhookSecret,
            @Value("${payment.stripe.return-base-url:}") String returnBaseUrl,
            @Value("${payment.stripe.api-base-url:https://api.stripe.com}") String apiBaseUrl) {
        if ("simulation".equals(provider)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))
                    || !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
                throw new IllegalStateException("Payment simulation requires dev/test and is forbidden with prod");
            }
            return new SimulationPaymentGateway();
        }
        if ("stripe".equals(provider)) {
            boolean production=environment.acceptsProfiles(Profiles.of("prod"));
            if(stripeWebhookSecret==null || !stripeWebhookSecret.startsWith("whsec_") || stripeWebhookSecret.length()<16)
                throw new IllegalStateException("Stripe requires a webhook signing secret for the selected mode");
            return new StripePaymentGateway(stripeSecret,returnBaseUrl,apiBaseUrl,production);
        }
        if (!"disabled".equals(provider)) throw new IllegalStateException("Unsupported payment provider: " + provider);
        return new PaymentGateway() {
            @Override public String provider() { return "disabled"; }
            @Override public Set<PaymentMethod> supportedMethods() { return Set.of(PaymentMethod.CASH_ON_DELIVERY,PaymentMethod.CASH_AT_PICKUP); }
            @Override public void requireAvailable() {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider is not configured");
            }
            @Override public Authorization authorize(Long orderId, BigDecimal amount, String currency, PaymentMethod method,
                                                       String idempotencyKey, boolean simulateFailure) {
                requireAvailable();
                throw new IllegalStateException("Payment provider is unavailable");
            }
        };
    }
}
