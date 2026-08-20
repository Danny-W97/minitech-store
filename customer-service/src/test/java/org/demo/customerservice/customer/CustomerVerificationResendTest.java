package org.demo.customerservice.customer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerVerificationResendTest {

    @Test
    void permitsThreeResendsAndThenAppliesTheWindowLimit() {
        Customer customer = customerWithVerification();
        Instant firstSentAt = customer.getVerificationLastSentAt();

        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant resendAt = firstSentAt.plusSeconds(attempt * 60L);
            assertThat(customer.canResendVerification(resendAt, 60, 3, 3600)).isTrue();
            customer.resendVerification("hash-" + attempt, resendAt.plusSeconds(300), resendAt, 3600);
        }

        assertThat(customer.getVerificationResendCount()).isEqualTo(3);
        assertThat(customer.canResendVerification(firstSentAt.plusSeconds(240), 60, 3, 3600)).isFalse();
        assertThat(customer.canResendVerification(firstSentAt.plusSeconds(3660), 60, 3, 3600)).isTrue();
    }

    @Test
    void aNewResendTokenReplacesThePreviousTokenAndRefreshesItsExpiry() {
        Customer customer = customerWithVerification();
        Instant firstSentAt = customer.getVerificationLastSentAt();
        Instant resendAt = firstSentAt.plusSeconds(60);

        customer.resendVerification("new-hash", resendAt.plusSeconds(300), resendAt, 3600);

        assertThat(customer.getVerificationTokenHash()).isEqualTo("new-hash");
        assertThat(customer.getVerificationExpiresAt()).isEqualTo(resendAt.plusSeconds(300));
        assertThat(customer.getVerificationResendCount()).isEqualTo(1);
    }

    private Customer customerWithVerification() {
        Customer customer = new Customer(
                "test@example.com", "password-hash", "Test", "User",
                "Street 1", "00-000", "Warsaw", "Polska"
        );
        customer.startVerification("initial-hash", Instant.now().plusSeconds(300));
        return customer;
    }
}
