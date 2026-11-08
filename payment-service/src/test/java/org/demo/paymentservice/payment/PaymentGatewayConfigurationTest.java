package org.demo.paymentservice.payment;

import org.demo.paymentservice.config.PaymentGatewayConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class PaymentGatewayConfigurationTest {
    private ApplicationContextRunner context(String provider, String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(PaymentGatewayConfiguration.class)
                .withPropertyValues("payment.provider=" + provider)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test void developmentMustExplicitlySelectSimulation() {
        context("simulation", "dev").run(ctx -> assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(SimulationPaymentGateway.class));
        context("disabled", "dev").run(ctx -> assertThatThrownBy(() -> ctx.getBean(PaymentGateway.class).requireAvailable())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503"));
    }
    @Test void simulationWorksInTestProfile() {
        context("simulation", "test").run(ctx -> {
            var result = ctx.getBean(PaymentGateway.class).authorize(1L, new BigDecimal("12.34"), "PLN", PaymentMethod.CARD, "key", false);
            assertThat(result.approved()).isTrue();
            assertThat(result.reference()).startsWith("SIM-");
        });
    }
    @Test void productionCannotEnableSimulation() {
        context("simulation", "prod").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void addingDevelopmentToProductionCannotBypassGuard() {
        context("simulation", "prod", "dev").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void simulationIsForbiddenOutsideDevelopmentAndTest() {
        context("simulation", "staging").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void stripeCannotStartInProductionWithoutModeMatchedSecrets() {
        context("stripe", "prod").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void stripeRequiresWebhookSecretBeforeStartingCheckoutClient() {
        context("stripe", "test")
                .withPropertyValues("payment.stripe.secret-key=sk_test_local-placeholder",
                        "payment.stripe.return-base-url=http://127.0.0.1:18070/index.html")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void missingProductionProviderCannotApprovePayment() {
        new ApplicationContextRunner().withUserConfiguration(PaymentGatewayConfiguration.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod")).run(ctx -> {
                    var gateway = ctx.getBean(PaymentGateway.class);
                    assertThatThrownBy(() -> gateway.authorize(1L, BigDecimal.TEN, "PLN", PaymentMethod.CARD, "key", false))
                            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
                });
    }
}
